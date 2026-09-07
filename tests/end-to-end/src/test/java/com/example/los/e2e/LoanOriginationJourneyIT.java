package com.example.los.e2e;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.los.testsupport.SensitiveMarkers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The journey the platform exists for, driven the way a client drives it.
 *
 * <p>Draft, upload a document through a presigned URL with no credentials, wait
 * for it to be scanned, submit, and watch the assessment run across three other
 * services and come back with a decision — with an audit trail that can be
 * verified afterwards.
 *
 * <p>Nothing is simulated between the services: the events genuinely travel
 * through Kafka, the outboxes genuinely drain, and the document genuinely lands
 * in S3.
 */
class LoanOriginationJourneyIT extends AbstractEndToEndTest {

    @Test
    @DisplayName("an application goes from draft to a decision, and the audit trail records every step")
    void theWholeJourney() {
        String token = platform.applicantToken();
        String suffix = uniqueSuffix();

        // -- Draft ------------------------------------------------------------
        HttpResponse<String> created = post(
                platform.applicationServiceUrl() + "/v1/applications", token, applicationRequest(suffix));

        assertThat(created.statusCode()).isEqualTo(201);
        String applicationId = json(created).get("applicationId").asString();
        assertThat(json(created).get("status").asString()).isEqualTo("DRAFT");

        // -- The two mandatory documents --------------------------------------
        // The document service publishes an event per accepted document; the
        // application service consumes it into its own read model. Submission is
        // refused until that projection has caught up, so the wait inside this
        // step is a wait for a real cross-service event, not a sleep.
        uploadBothMandatoryDocuments(applicationId, token);

        // -- Submit -----------------------------------------------------------
        HttpResponse<String> submitted = post(
                platform.applicationServiceUrl() + "/v1/applications/" + applicationId + "/submit",
                token,
                null,
                "submit-" + suffix);

        // 202, not 200: submission starts an assessment that finishes later. The
        // response says the request was accepted, not that a decision was reached.
        assertThat(submitted.statusCode()).isEqualTo(202);

        // -- The assessment, across three services ----------------------------
        Awaitility.await("a decision is reached")
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> {
                    HttpResponse<String> status = get(
                            platform.applicationServiceUrl() + "/v1/applications/" + applicationId + "/status", token);
                    assertThat(status.statusCode()).isEqualTo(200);
                    assertThat(json(status).get("status").asString()).isEqualTo("APPROVED");
                });

        // -- Every outbox drained ---------------------------------------------
        // A pending row here would mean an event was written and never published,
        // which is the failure the outbox pattern exists to make impossible.
        Awaitility.await("every outbox is drained")
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> {
                    assertThat(countRows(
                                    "SELECT count(*) FROM application.outbox_event WHERE status <> 'PUBLISHED'"))
                            .isZero();
                    assertThat(countRows("SELECT count(*) FROM workflow.outbox_event WHERE status <> 'PUBLISHED'"))
                            .isZero();
                    assertThat(countRows("SELECT count(*) FROM document.outbox_event WHERE status <> 'PUBLISHED'"))
                            .isZero();
                });

        // -- The audit trail ---------------------------------------------------
        String applicantReference = queryString(
                "SELECT applicant_reference FROM application.loan_application WHERE id = ?::uuid", applicationId);
        assertThat(applicantReference).isNotBlank();

        Awaitility.await("the audit trail records the whole journey")
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> {
                    long records = countRows(
                            "SELECT count(*) FROM audit.audit_record WHERE chain_key = ?", applicationId);
                    // Submission, the document events, the workflow's start and
                    // completion, and the decision. The exact number depends on
                    // how many checks ran, so the assertion is on the shape of the
                    // trail rather than on a magic number that would break every
                    // time a step is added.
                    assertThat(records).isGreaterThanOrEqualTo(4L);
                });

        assertThat(auditEventTypes(applicationId))
                .contains("application.submitted")
                .contains("workflow.completed")
                .contains("application.decision-recorded");

        // -- The chain is intact ----------------------------------------------
        assertThat(auditChainIsSequential(applicationId))
                .withFailMessage("The audit chain for %s has a gap or a repeated sequence number.", applicationId)
                .isTrue();

        // -- No personal data escaped -----------------------------------------
        assertNoPersonalDataInAudit(applicationId);
        assertNoPersonalDataInServiceLogs();

        // The API identifies the applicant by pseudonym, never by the values that
        // were supplied.
        HttpResponse<String> fetched =
                get(platform.applicationServiceUrl() + "/v1/applications/" + applicationId, token);
        assertThat(fetched.body())
                .contains("applicantReference")
                .doesNotContain("Applicant" + suffix)
                .doesNotContain("e2e-" + suffix.toLowerCase() + "@example.com");
    }

    // -------------------------------------------------------------------------
    // Steps
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // Assertions about the audit trail
    // -------------------------------------------------------------------------

    private static String auditEventTypes(String applicationId) {
        return withDatabase(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT string_agg(event_type, ',' ORDER BY sequence_number) "
                            + "FROM audit.audit_record WHERE chain_key = ?")) {
                statement.setObject(1, applicationId);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? String.valueOf(rows.getString(1)) : "";
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Could not read the audit trail", e);
            }
        });
    }

    /**
     * Checks the chain has no gaps and no repeats.
     *
     * <p>The cryptographic verification is the audit service's own test. What
     * matters here is that a journey crossing four services produced one
     * unbroken sequence, which is the property that would break first if two
     * consumers ever appended concurrently.
     */
    private static boolean auditChainIsSequential(String applicationId) {
        long records = countRows("SELECT count(*) FROM audit.audit_record WHERE chain_key = ?", applicationId);
        long distinctSequences =
                countRows("SELECT count(DISTINCT sequence_number) FROM audit.audit_record WHERE chain_key = ?",
                        applicationId);
        long highest = countRows(
                "SELECT coalesce(max(sequence_number), 0) FROM audit.audit_record WHERE chain_key = ?", applicationId);

        return records > 0 && records == distinctSequences && records == highest;
    }

    private static void assertNoPersonalDataInAudit(String applicationId) {
        String everything = withDatabase(connection -> {
            try (var statement = connection.prepareStatement(
                    "SELECT coalesce(string_agg(event_type || ' ' || summary::text, ' '), '') "
                            + "FROM audit.audit_record WHERE chain_key = ?")) {
                statement.setObject(1, applicationId);
                try (var rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : "";
                }
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("Could not read the audit trail", e);
            }
        });

        for (String marker : SensitiveMarkers.all()) {
            assertThat(everything)
                    .withFailMessage("Sensitive marker '%s' reached the audit trail.", marker)
                    .doesNotContain(marker);
        }
        // The applicant's actual details, not just the synthetic markers.
        assertThat(everything).doesNotContain("Testine").doesNotContain("@example.com");
    }

    /**
     * Reads what the four services actually logged.
     *
     * <p>Each service's own tests assert this against its logging pipeline. Doing
     * it again here catches the case those cannot: a value that is safe in the
     * service that owns it and leaks in a service downstream, after it has
     * crossed an event boundary.
     */
    private static void assertNoPersonalDataInServiceLogs() {
        try (var logFiles = java.nio.file.Files.list(platform.logDirectory())) {
            logFiles.forEach(logFile -> {
                String contents;
                try {
                    contents = java.nio.file.Files.readString(logFile, StandardCharsets.UTF_8);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException("Could not read " + logFile, e);
                }
                assertThat(contents)
                        .withFailMessage("An applicant's name reached %s", logFile.getFileName())
                        .doesNotContain("Testine");
                assertThat(contents)
                        .withFailMessage("An applicant's email address reached %s", logFile.getFileName())
                        .doesNotContain("@example.com");
            });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Could not list the service logs", e);
        }
    }
}
