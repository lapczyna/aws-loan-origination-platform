package com.example.los.e2e;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What submission promises, checked across the whole platform rather than
 * against one service.
 *
 * <p>Each service's own tests already cover these semantics in isolation. The
 * reason to check them again here is that submission is the point where the
 * application context depends on a projection of state owned by the document
 * context, and a guarantee that holds inside one service can still be wrong once
 * it depends on an event arriving.
 */
class SubmissionSemanticsIT extends AbstractEndToEndTest {

    @Test
    @DisplayName("a repeated idempotency key replays the original submission instead of submitting twice")
    void repeatedKeyReplaysTheOriginalSubmission() {
        String token = platform.applicantToken();
        String suffix = uniqueSuffix();
        String applicationId = createDraft(token, applicationRequest(suffix));
        uploadBothMandatoryDocuments(applicationId, token);

        String key = "submit-" + suffix;
        String submitUrl = platform.applicationServiceUrl() + "/v1/applications/" + applicationId + "/submit";

        HttpResponse<String> first = post(submitUrl, token, null, key);
        HttpResponse<String> second = post(submitUrl, token, null, key);

        assertThat(first.statusCode()).isEqualTo(202);
        assertThat(second.statusCode()).isEqualTo(202);
        assertThat(second.headers().firstValue("Idempotent-Replay")).contains("true");

        // The decisive assertion is not the status code but the effect: exactly
        // one submission event, so the workflow context starts exactly one
        // assessment. A replay that returned 202 and published a second event
        // would look correct and assess the application twice.
        assertThat(countRows(
                        "SELECT count(*) FROM application.outbox_event "
                                + "WHERE aggregate_id = ? AND event_type = 'application.submitted'",
                        applicationId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the same idempotency key used for a different application is a conflict, not a silent replay")
    void reusedKeyForADifferentApplicationConflicts() {
        String token = platform.applicantToken();
        String key = "shared-key-" + uniqueSuffix();

        String firstApplication = createDraft(token, applicationRequest(uniqueSuffix()));
        uploadBothMandatoryDocuments(firstApplication, token);
        String secondApplication = createDraft(token, applicationRequest(uniqueSuffix()));
        uploadBothMandatoryDocuments(secondApplication, token);

        HttpResponse<String> first = post(
                platform.applicationServiceUrl() + "/v1/applications/" + firstApplication + "/submit",
                token,
                null,
                key);
        HttpResponse<String> second = post(
                platform.applicationServiceUrl() + "/v1/applications/" + secondApplication + "/submit",
                token,
                null,
                key);

        assertThat(first.statusCode()).isEqualTo(202);

        // 409, not a replay of the first application's response. Returning the
        // first result here would tell a client its second application had been
        // submitted when it had not -- the most dangerous possible answer.
        assertThat(second.statusCode()).isEqualTo(409);
        assertThat(second.body()).contains("IDEMPOTENCY_KEY_REUSED");

        assertThat(queryString("SELECT status FROM application.loan_application WHERE id = ?::uuid", secondApplication))
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("concurrent submissions with one key submit the application exactly once")
    void concurrentSubmissionsSubmitOnce() throws Exception {
        String token = platform.applicantToken();
        String suffix = uniqueSuffix();
        String applicationId = createDraft(token, applicationRequest(suffix));
        uploadBothMandatoryDocuments(applicationId, token);

        String key = "concurrent-" + suffix;
        String submitUrl = platform.applicationServiceUrl() + "/v1/applications/" + applicationId + "/submit";

        int concurrency = 6;
        List<Callable<HttpResponse<String>>> attempts = new ArrayList<>();
        for (int i = 0; i < concurrency; i++) {
            attempts.add(() -> post(submitUrl, token, null, key));
        }

        List<HttpResponse<String>> responses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(concurrency)) {
            for (Future<HttpResponse<String>> result : pool.invokeAll(attempts)) {
                responses.add(result.get());
            }
        }

        // Every caller gets a usable answer. A 500 here would mean the race was
        // resolved by a constraint violation escaping to the client rather than
        // by the idempotency mechanism.
        assertThat(responses).allSatisfy(response -> assertThat(response.statusCode())
                .withFailMessage("A concurrent submission returned %s: %s", response.statusCode(), response.body())
                .isIn(202, 409));

        // And exactly one submission actually happened.
        assertThat(countRows(
                        "SELECT count(*) FROM application.outbox_event "
                                + "WHERE aggregate_id = ? AND event_type = 'application.submitted'",
                        applicationId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an application with a missing mandatory document cannot be submitted")
    void missingMandatoryDocumentBlocksSubmission() {
        String token = platform.applicantToken();
        String suffix = uniqueSuffix();
        String applicationId = createDraft(token, applicationRequest(suffix));

        // Identity only. Proof of income is mandatory and never supplied.
        uploadAcceptedDocument(applicationId, token, "PROOF_OF_IDENTITY");

        HttpResponse<String> submitted = post(
                platform.applicationServiceUrl() + "/v1/applications/" + applicationId + "/submit", token, null);

        assertThat(submitted.statusCode()).isEqualTo(422);
        assertThat(submitted.body()).contains("MANDATORY_DOCUMENTS_MISSING");

        // The error names the problem without naming the applicant.
        assertThat(submitted.body()).doesNotContain("Testine").doesNotContain("@example.com");

        assertThat(queryString("SELECT status FROM application.loan_application WHERE id = ?::uuid", applicationId))
                .isEqualTo("DRAFT");
        assertThat(countRows(
                        "SELECT count(*) FROM application.outbox_event "
                                + "WHERE aggregate_id = ? AND event_type = 'application.submitted'",
                        applicationId))
                .isZero();
    }

    @Test
    @DisplayName("a mandatory document whose upload was abandoned does not satisfy the requirement")
    void abandonedUploadDoesNotSatisfyAMandatoryRequirement() {
        String token = platform.applicantToken();
        String suffix = uniqueSuffix();
        String applicationId = createDraft(token, applicationRequest(suffix));

        uploadAcceptedDocument(applicationId, token, "PROOF_OF_IDENTITY");

        // A slot is requested and then nothing is uploaded: the client closed the
        // tab. The document exists, so a naive check for "is there a proof of
        // income?" would pass, and this is the case that must not.
        //
        // The malware-rejection path reaches the same guard from the other side
        // and is NOT reachable from here: the simulated scanner keys on the S3
        // object key, which is an opaque identifier by design precisely so it
        // cannot carry anything a client chose. It is covered by the document
        // service's own DocumentUploadIT.
        requestUploadSlot(applicationId, token, "PROOF_OF_INCOME");

        HttpResponse<String> submitted = post(
                platform.applicationServiceUrl() + "/v1/applications/" + applicationId + "/submit", token, null);

        assertThat(submitted.statusCode()).isEqualTo(422);
        assertThat(submitted.body()).contains("MANDATORY_DOCUMENTS_MISSING");
        assertThat(queryString("SELECT status FROM application.loan_application WHERE id = ?::uuid", applicationId))
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName("a draft cannot be edited once it has been submitted")
    void submittedApplicationsAreNoLongerDrafts() {
        String token = platform.applicantToken();
        String suffix = uniqueSuffix();
        String applicationId = createDraft(token, applicationRequest(suffix));
        uploadBothMandatoryDocuments(applicationId, token);

        HttpResponse<String> submitted = post(
                platform.applicationServiceUrl() + "/v1/applications/" + applicationId + "/submit", token, null);
        assertThat(submitted.statusCode()).isEqualTo(202);

        HttpResponse<String> edited = putJson(
                platform.applicationServiceUrl() + "/v1/applications/" + applicationId + "/draft",
                token,
                applicationRequest(uniqueSuffix()));

        // Changing the amount after submission would mean the assessment ran
        // against figures nobody submitted.
        assertThat(edited.statusCode()).isIn(409, 422);
    }
}
