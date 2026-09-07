package com.example.los.e2e;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import tools.jackson.databind.JsonNode;

import com.example.los.events.EventJson;
import com.example.los.testsupport.PlatformContainers;

/**
 * Everything the end-to-end scenarios need, and nothing that would let them
 * cheat.
 *
 * <p>The only ways in are the ones a real client has: HTTP to the two services
 * that expose an API, and JDBC to look at what was actually persisted. No test
 * calls a use case, injects a bean, or reaches into a service's internals --
 * those are covered by each service's own integration tests, and doing it here
 * would prove that the pieces work rather than that they work together.
 */
abstract class AbstractEndToEndTest {

    protected static final PlatformUnderTest platform = PlatformUnderTest.start();

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Emptied before every test.
     *
     * <p>Truncating rather than recreating the schemas keeps the migrations
     * applied once per run, which is the expensive part, while still giving each
     * test an empty platform. The services keep running throughout; restarting
     * four JVMs per test would make the suite unusable.
     *
     * <p>The audit table is truncated too, which the runtime role could not do.
     * These statements run as the container's superuser, deliberately: the point
     * of the append-only grant is that the SERVICE cannot delete audit records,
     * not that nobody can.
     */
    /**
     * The tables each test starts empty, children before parents.
     *
     * <p>DELETE rather than TRUNCATE, and that is not a style preference.
     * TRUNCATE needs an AccessExclusiveLock on every table at once, while the
     * four services keep running throughout the suite and their pollers hold
     * AccessShareLocks on some of those tables. The two lock orders cross, and
     * PostgreSQL resolves it exactly as it should: {@code deadlock detected},
     * intermittently, in whichever test happened to run while a poller was
     * awake. DELETE takes row locks and does not fight the pollers for the
     * table.
     */
    private static final List<String> TABLES_TO_EMPTY = List.of(
            "application.outbox_event",
            "application.processed_event",
            "application.idempotency_key",
            "application.document_status",
            "application.application_version",
            "application.loan_application",
            "workflow.outbox_event",
            "workflow.processed_event",
            "workflow.workflow_check",
            "workflow.workflow_instance",
            "document.outbox_event",
            "document.processed_event",
            "document.document",
            "audit.processed_event",
            // Deleted as the container's superuser. The runtime role holds
            // SELECT and INSERT only, and that is the point of the grant: the
            // SERVICE cannot delete an audit record, not that nobody can.
            "audit.audit_record");

    @BeforeEach
    void resetPlatform() {
        // Retried, because even DELETE can lose a race with a poller mid-write.
        // Three attempts with a short pause: a genuine problem still fails, and a
        // momentary collision does not fail an unrelated test.
        IllegalStateException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                emptyEveryTable();
                return;
            } catch (IllegalStateException collision) {
                lastFailure = collision;
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while resetting the platform", e);
                }
            }
        }
        throw new IllegalStateException("Could not reset the platform between tests", lastFailure);
    }

    private void emptyEveryTable() {
        withDatabase(connection -> {
            try (Statement statement = connection.createStatement()) {
                // Fail fast rather than block behind a poller's transaction until
                // the test times out with nothing useful to say.
                statement.execute("SET lock_timeout = '10s'");
                for (String table : TABLES_TO_EMPTY) {
                    statement.execute("DELETE FROM " + table);
                }
                return null;
            } catch (SQLException e) {
                throw new IllegalStateException("Could not empty the platform's tables", e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // HTTP
    // -------------------------------------------------------------------------

    /** A POST with a JSON body, returning the raw response so errors can be asserted on. */
    protected HttpResponse<String> post(String url, String token, Object body, String idempotencyKey) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(
                        body == null ? "{}" : EventJson.mapper().writeValueAsString(body), StandardCharsets.UTF_8));

        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return send(request.build());
    }

    protected HttpResponse<String> post(String url, String token, Object body) {
        return post(url, token, body, null);
    }

    protected HttpResponse<String> putJson(String url, String token, Object body) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .PUT(HttpRequest.BodyPublishers.ofString(
                        EventJson.mapper().writeValueAsString(body), StandardCharsets.UTF_8))
                .build());
    }

    protected HttpResponse<String> get(String url, String token) {
        return send(HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build());
    }

    /** A raw PUT with no credentials at all — exactly what a browser does with a presigned URL. */
    protected HttpResponse<String> putWithoutCredentials(String url, byte[] content, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(content));

        headers.forEach(request::header);
        return send(request.build());
    }

    private static HttpResponse<String> send(HttpRequest request) {
        try {
            return HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Request to " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during a request to " + request.uri(), e);
        }
    }

    protected static JsonNode json(HttpResponse<String> response) {
        return EventJson.mapper().readTree(response.body());
    }

    // -------------------------------------------------------------------------
    // Database
    // -------------------------------------------------------------------------

    /**
     * Opens a connection to the shared PostgreSQL container.
     *
     * <p>A connection per call rather than a pool: these are assertions, not a
     * workload, and a pool held open across four service processes is one more
     * thing to reason about when a test hangs.
     */
    protected static <T> T withDatabase(Function<Connection, T> work) {
        try (Connection connection = DriverManager.getConnection(
                PlatformContainers.postgres().getJdbcUrl(),
                PlatformContainers.postgres().getUsername(),
                PlatformContainers.postgres().getPassword())) {
            return work.apply(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Database access failed", e);
        }
    }

    protected static long countRows(String sql, Object... parameters) {
        return withDatabase(connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Query failed: " + sql, e);
            }
        });
    }

    protected static String queryString(String sql, Object... parameters) {
        return withDatabase(connection -> {
            try (var statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setObject(i + 1, parameters[i]);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    return rows.next() ? rows.getString(1) : null;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Query failed: " + sql, e);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Journey steps shared by several scenarios
    // -------------------------------------------------------------------------

    protected static final byte[] SYNTHETIC_DOCUMENT =
            "%PDF-1.4 synthetic end-to-end document. Not a real file and not a real person's data."
                    .getBytes(StandardCharsets.UTF_8);

    /** Creates a draft and returns its identifier. */
    protected String createDraft(String token, Map<String, Object> request) {
        HttpResponse<String> created =
                post(platform.applicationServiceUrl() + "/v1/applications", token, request);
        org.assertj.core.api.Assertions.assertThat(created.statusCode())
                .withFailMessage("Creating a draft failed: %s", created.body())
                .isEqualTo(201);
        return json(created).get("applicationId").asString();
    }

    /** Requests an upload slot and returns the ticket, without uploading anything. */
    protected JsonNode requestUploadSlot(String applicationId, String token, String documentType) {
        Map<String, Object> uploadRequest = new LinkedHashMap<>();
        uploadRequest.put("documentType", documentType);
        uploadRequest.put("contentType", "application/pdf");
        uploadRequest.put("sizeBytes", SYNTHETIC_DOCUMENT.length);

        HttpResponse<String> ticket = post(
                platform.documentServiceUrl() + "/v1/applications/" + applicationId + "/documents/upload-requests",
                token,
                uploadRequest);

        org.assertj.core.api.Assertions.assertThat(ticket.statusCode())
                .withFailMessage("Upload request for %s failed: %s", documentType, ticket.body())
                .isEqualTo(201);
        // A presigned URL is a bearer credential. It must never be cacheable.
        org.assertj.core.api.Assertions.assertThat(
                        ticket.headers().firstValue("Cache-Control").orElse(""))
                .contains("no-store");
        return json(ticket);
    }

    /**
     * Requests an upload slot, PUTs the bytes with no credentials, confirms the
     * upload, and waits for the scanner to accept it.
     *
     * <p>The PUT is made by a plain HTTP client holding no AWS credentials —
     * exactly the position a browser is in. That is the only way to establish
     * that the presigned URL the service hands out is actually usable; a test
     * that called the SDK would prove the SDK works.
     */
    protected void uploadAcceptedDocument(String applicationId, String token, String documentType) {
        Map<String, Object> uploadRequest = new LinkedHashMap<>();
        uploadRequest.put("documentType", documentType);
        uploadRequest.put("contentType", "application/pdf");
        uploadRequest.put("sizeBytes", SYNTHETIC_DOCUMENT.length);

        HttpResponse<String> ticket = post(
                platform.documentServiceUrl() + "/v1/applications/" + applicationId + "/documents/upload-requests",
                token,
                uploadRequest);

        org.assertj.core.api.Assertions.assertThat(ticket.statusCode())
                .withFailMessage("Upload request for %s failed: %s", documentType, ticket.body())
                .isEqualTo(201);

        // A presigned URL is a bearer credential. It must never be cacheable.
        org.assertj.core.api.Assertions.assertThat(ticket.headers().firstValue("Cache-Control").orElse("")).contains("no-store");

        JsonNode body = json(ticket);
        String documentId = body.get("documentId").asString();
        String uploadUrl = body.get("uploadUrl").asString();

        Map<String, String> requiredHeaders = new LinkedHashMap<>();
        body.get("requiredHeaders").properties().forEach(entry -> requiredHeaders.put(
                entry.getKey(), entry.getValue().asString()));

        HttpResponse<String> uploaded = putWithoutCredentials(uploadUrl, SYNTHETIC_DOCUMENT, requiredHeaders);
        org.assertj.core.api.Assertions.assertThat(uploaded.statusCode())
                .withFailMessage("The presigned PUT was refused: %s", uploaded.body())
                .isEqualTo(200);

        HttpResponse<String> completed = post(
                platform.documentServiceUrl() + "/v1/applications/" + applicationId + "/documents/" + documentId
                        + "/complete",
                token,
                Map.of());
        org.assertj.core.api.Assertions.assertThat(completed.statusCode())
                .withFailMessage("Completing the upload failed: %s", completed.body())
                .isEqualTo(200);

        org.awaitility.Awaitility.await(documentType + " is scanned and accepted")
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(
                                queryString("SELECT status FROM document.document WHERE id = ?::uuid", documentId))
                        .isEqualTo("CLEAN"));
    }

    /** Uploads and accepts both mandatory documents, then waits for the projection. */
    protected void uploadBothMandatoryDocuments(String applicationId, String token) {
        uploadAcceptedDocument(applicationId, token, "PROOF_OF_IDENTITY");
        uploadAcceptedDocument(applicationId, token, "PROOF_OF_INCOME");

        org.awaitility.Awaitility.await("both documents visible to the application service")
                .atMost(PlatformUnderTest.patience())
                .untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(countRows(
                                "SELECT count(*) FROM application.document_status "
                                        + "WHERE application_id = ?::uuid AND status = 'CLEAN'",
                                applicationId))
                        .isEqualTo(2));
    }

    // -------------------------------------------------------------------------
    // Request bodies
    // -------------------------------------------------------------------------

    /**
     * A valid application for a synthetic applicant.
     *
     * <p>Every value is invented. The email address uses the RFC 2606 reserved
     * example.com domain and the names are obviously fake.
     */
    /**
     * Synthetic applicants chosen so that the decision is DETERMINISTIC.
     *
     * <p>This matters more than it looks. The simulated credit bureau derives a
     * stable score from the applicant reference, the reference is an HMAC over
     * the applicant's family name, given name, date of birth and country, and
     * the policy bands that score: below 450 rejects, 450 to 649 goes to a human,
     * 650 and above approves. A randomly generated applicant therefore approves
     * roughly half the time — so a test asserting APPROVED for a random applicant
     * is a coin flip that passes locally and fails in CI, which is exactly how a
     * suite stops being trusted.
     *
     * <p>These three family names were computed from that derivation and then
     * confirmed by running them. They depend on the pepper in
     * {@code PlatformUnderTest.writeSyntheticPepper()}: changing that string
     * changes every reference and moves every applicant to a different band.
     */
    protected static final String APPLICANT_AUTO_APPROVE = "ApplicantE2E00004";

    protected static final String APPLICANT_REVIEW_BAND = "ApplicantE2E00000";

    protected static final String APPLICANT_REJECT_BAND = "ApplicantE2E00003";

    protected static Map<String, Object> applicationRequest(String applicantSuffix) {
        return applicationRequest(applicantSuffix, PRODUCT_STANDARD);
    }

    /**
     * The product code the simulated providers behave normally for.
     *
     * <p>The simulators pick their behaviour from the product code, because the
     * applicant reference that reaches the workflow context is an HMAC pseudonym
     * and can never contain a scenario marker. That is what makes a permanent
     * rejection or a route to manual review reachable through the real API
     * rather than only by calling the workflow context directly.
     */
    protected static final String PRODUCT_STANDARD = "PERSONAL-LOAN-STANDARD";

    protected static Map<String, Object> applicationRequest(String applicantSuffix, String productCode) {
        return applicationRequest(APPLICANT_AUTO_APPROVE, applicantSuffix, productCode);
    }

    /**
     * An application for a named synthetic applicant.
     *
     * <p>The family name decides the credit band; the suffix only varies the
     * email address, which is deliberately NOT part of the reference derivation,
     * so two applications by the same applicant share a reference exactly as
     * they would in production.
     */
    protected static Map<String, Object> applicationRequest(
            String familyName, String applicantSuffix, String productCode) {
        Map<String, Object> applicant = new LinkedHashMap<>();
        applicant.put("givenName", "Testine");
        applicant.put("familyName", familyName);
        applicant.put("emailAddress", "e2e-" + applicantSuffix.toLowerCase() + "@example.com");
        applicant.put("dateOfBirth", LocalDate.of(1990, 5, 17).toString());
        applicant.put("residenceCountry", "PL");

        Map<String, Object> loan = new LinkedHashMap<>();
        loan.put("amountMinorUnits", 1_500_000L);
        loan.put("currency", "EUR");
        loan.put("termMonths", 36);
        loan.put("purpose", "HOME_IMPROVEMENT");
        loan.put("declaredAnnualIncomeMinorUnits", 6_000_000L);
        loan.put("productCode", productCode);

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("applicant", applicant);
        request.put("loan", loan);
        return request;
    }

    protected static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }
}
