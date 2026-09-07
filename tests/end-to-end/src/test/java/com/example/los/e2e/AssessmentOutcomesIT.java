package com.example.los.e2e;

import java.net.http.HttpResponse;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way an assessment can end, driven through the real API.
 *
 * <p>The simulated providers choose their behaviour from the product code. That
 * is what makes these scenarios reachable from the front door at all: the
 * applicant reference the workflow context sees is an HMAC pseudonym, so a
 * caller cannot select a behaviour by choosing an applicant, and every failure
 * mode would otherwise be reachable only by calling the workflow context
 * directly — which is to say, only by a test that skips the parts most worth
 * testing.
 *
 * <p><strong>The providers are simulations.</strong> Nothing here contacts a
 * real KYC, AML, fraud or credit system, and no adapter in this repository does.
 */
class AssessmentOutcomesIT extends AbstractEndToEndTest {

    @Test
    @DisplayName("an applicant who fails a check is rejected, and the reason is a code rather than prose")
    void businessRejectionIsRecordedWithAReasonCode() {
        String applicationId = submitWithProduct("BUSINESS_REJECTION");

        awaitStatus(applicationId, "REJECTED");

        // A stable reason code, not a sentence. Prose about why a named person
        // was refused credit is exactly what must not travel through events into
        // the audit store.
        String decisionReason =
                queryString("SELECT decision_reason_code FROM application.loan_application WHERE id = ?::uuid", applicationId);
        assertThat(decisionReason).isNotBlank().doesNotContain(" ");

        // Waited for, not asserted immediately: the decision reaches the audit
        // trail through Kafka, so the application row is REJECTED slightly before
        // the audit consumer has processed the event. Asserting straight away
        // passes on a fast machine and fails on a loaded one, which is the
        // classic way an end-to-end suite becomes something people rerun.
        Awaitility.await("the rejection reaches the audit trail")
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> assertThat(auditEventTypes(applicationId))
                        .contains("application.decision-recorded"));
    }

    @Test
    @DisplayName("a check that cannot decide sends the application to a human, and a reviewer can decide it")
    void inconclusiveCheckRoutesToManualReviewAndAReviewerDecides() {
        String applicationId = submitWithProduct("INCONCLUSIVE");

        awaitStatus(applicationId, "MANUAL_REVIEW");

        // The task appears in the reviewer's queue.
        String reviewerToken = platform.reviewerToken("reviewer-e2e-001");
        Awaitility.await("the application appears in the manual review queue")
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> {
                    HttpResponse<String> queue =
                            get(platform.applicationServiceUrl() + "/v1/manual-review/tasks", reviewerToken);
                    assertThat(queue.statusCode()).isEqualTo(200);
                    assertThat(queue.body()).contains(applicationId);
                });

        // An applicant-scoped token must not be able to decide it.
        HttpResponse<String> refused = post(
                platform.applicationServiceUrl() + "/v1/manual-review/tasks/" + applicationId + "/decisions",
                platform.applicantToken(),
                java.util.Map.of("decision", "APPROVED"));
        assertThat(refused.statusCode()).isEqualTo(403);

        HttpResponse<String> decided = post(
                platform.applicationServiceUrl() + "/v1/manual-review/tasks/" + applicationId + "/decisions",
                reviewerToken,
                java.util.Map.of("decision", "APPROVED"));

        assertThat(decided.statusCode()).isEqualTo(200);
        assertThat(json(decided).get("status").asString()).isEqualTo("APPROVED");

        // The decision is attributed to the token's subject, never to a value the
        // client sent, so a caller cannot attribute a decision to someone else.
        Awaitility.await("the decision reaches the audit trail")
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> assertThat(auditEventTypes(applicationId))
                        .contains("application.decision-recorded"));

        assertThat(queryString(
                        "SELECT decision_decided_by FROM application.loan_application WHERE id = ?::uuid", applicationId))
                .isEqualTo("reviewer-e2e-001");
    }

    @Test
    @DisplayName("a provider that is briefly unavailable is retried, and the application still gets a decision")
    void transientProviderFailureIsRetriedAndRecovers() {
        String applicationId = submitWithProduct("TRANSIENT_FAILURE");

        // The first attempt at each check throws; the durable schedule retries.
        // The application must still reach a decision -- an outage at a bureau is
        // not an answer about the applicant.
        awaitStatus(applicationId, "APPROVED");

        // The retry genuinely happened rather than the first attempt succeeding.
        assertThat(countRows(
                        "SELECT count(*) FROM workflow.workflow_check c "
                                + "JOIN workflow.workflow_instance w ON w.id = c.workflow_id "
                                + "WHERE w.application_id = ?::uuid AND c.attempts > 1",
                        applicationId))
                .isPositive();
    }

    @Test
    @DisplayName("a provider that fails permanently abandons the check without recording an outcome about the applicant")
    void permanentProviderFailureRecordsNoOutcomeAboutTheApplicant() {
        String applicationId = submitWithProduct("PERMANENT_FAILURE");

        // Not APPROVED and not REJECTED: the platform could not assess this
        // application, which is a different thing from having assessed it badly.
        Awaitility.await("the application reaches a terminal state")
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> assertThat(currentStatus(applicationId))
                        .isIn("MANUAL_REVIEW", "FAILED"));

        // THE ASSERTION THAT MATTERS. An abandoned check records no outcome. If a
        // provider outage were written down as a failed check, "the bureau was
        // down" would later read as "the applicant failed", and nothing
        // downstream could tell the difference.
        assertThat(countRows(
                        "SELECT count(*) FROM workflow.workflow_check c "
                                + "JOIN workflow.workflow_instance w ON w.id = c.workflow_id "
                                + "WHERE w.application_id = ?::uuid AND c.outcome = 'FAILED'",
                        applicationId))
                .isZero();
    }

    @Test
    @DisplayName("an applicant whose credit score is below the threshold is rejected without a human touching it")
    void aScoreBelowTheThresholdIsRejectedAutomatically() {
        String applicationId = submitAs(APPLICANT_REJECT_BAND, PRODUCT_STANDARD);

        awaitStatus(applicationId, "REJECTED");

        // Rejected by the policy on a score, not by a provider refusing to answer.
        // Those are different things and the reason code has to say which.
        assertThat(queryString(
                        "SELECT decision_reason_code FROM application.loan_application WHERE id = ?::uuid",
                        applicationId))
                .isEqualTo("CREDIT_SCORE_BELOW_THRESHOLD");
    }

    @Test
    @DisplayName("an applicant in the review band goes to a human rather than being decided automatically")
    void aScoreInTheReviewBandGoesToAHuman() {
        String applicationId = submitAs(APPLICANT_REVIEW_BAND, PRODUCT_STANDARD);

        awaitStatus(applicationId, "MANUAL_REVIEW");

        // The recorded decision is MANUAL_REVIEW: the platform declined to
        // decide, which is emphatically not the same as deciding against the
        // applicant, and the record has to be able to tell those apart later.
        assertThat(queryString(
                        "SELECT decision_type FROM application.loan_application WHERE id = ?::uuid", applicationId))
                .isEqualTo("MANUAL_REVIEW")
                .isNotIn("APPROVED", "REJECTED");
    }

    // -------------------------------------------------------------------------

    /** Drafts, uploads both mandatory documents, and submits under a given product code. */
    private String submitWithProduct(String productCode) {
        return submitAs(APPLICANT_AUTO_APPROVE, "E2E-" + productCode);
    }

    /** The same, for a named applicant whose credit band is known. */
    private String submitAs(String familyName, String productCode) {
        String token = platform.applicantToken();
        String suffix = uniqueSuffix();

        String applicationId = createDraft(token, applicationRequest(familyName, suffix, productCode));
        uploadBothMandatoryDocuments(applicationId, token);

        HttpResponse<String> submitted = post(
                platform.applicationServiceUrl() + "/v1/applications/" + applicationId + "/submit",
                token,
                null,
                "submit-" + suffix);
        assertThat(submitted.statusCode())
                .withFailMessage("Submission failed: %s", submitted.body())
                .isEqualTo(202);

        return applicationId;
    }

    private void awaitStatus(String applicationId, String expected) {
        Awaitility.await("the application reaches " + expected)
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> assertThat(currentStatus(applicationId)).isEqualTo(expected));
    }

    private String currentStatus(String applicationId) {
        return queryString("SELECT status FROM application.loan_application WHERE id = ?::uuid", applicationId);
    }

    private static String auditEventTypes(String applicationId) {
        String types = queryString(
                "SELECT coalesce(string_agg(event_type, ',' ORDER BY sequence_number), '') "
                        + "FROM audit.audit_record WHERE chain_key = ?",
                applicationId);
        return types == null ? "" : types;
    }
}
