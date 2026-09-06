package com.example.los.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.example.los.testsupport.CapturedLogs;
import com.example.los.testsupport.SensitiveMarkers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the HTTP surface end to end: real tokens, real database, real
 * serialisation.
 *
 * <p>Covers the three guarantees the API makes that are easy to claim and hard
 * to get right — idempotency, error responses that leak nothing, and
 * authorisation that is actually enforced.
 */
class ApplicationApiIT extends AbstractIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private RestClient client;

    @BeforeEach
    void setUp() {
        resetDatabase();
        client = RestClient.builder()
                .baseUrl("http://localhost:" + port)
                // Errors are asserted on, so the client must not throw on them.
                .defaultStatusHandler(status -> true, (request, response) -> {})
                .build();
    }

    @Nested
    @DisplayName("idempotency")
    class Idempotency {

        @Test
        @DisplayName("the same key with the same body returns the original application")
        void repeatedRequestReturnsTheOriginalResult() {
            String key = "idem-" + UUID.randomUUID();
            Map<String, Object> body = validApplicationRequest();

            ResponseEntity<Map> first = post("/v1/applications", body, key);
            ResponseEntity<Map> second = post("/v1/applications", body, key);

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getBody().get("applicationId"))
                    .isEqualTo(first.getBody().get("applicationId"));
            assertThat(second.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");

            // Exactly one application exists: the replay did not create a second.
            assertThat(countApplications()).isEqualTo(1);
        }

        @Test
        @DisplayName("the same key with a different body is a conflict, not a silent replay")
        void reusedKeyWithDifferentBodyConflicts() {
            String key = "idem-" + UUID.randomUUID();

            ResponseEntity<Map> first = post("/v1/applications", validApplicationRequest(), key);
            Map<String, Object> differentBody = validApplicationRequest();
            ((Map<String, Object>) differentBody.get("loan")).put("amountMinorUnits", 2_000_000L);

            ResponseEntity<Map> second = post("/v1/applications", differentBody, key);

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(second.getBody().get("errorCode")).isEqualTo("IDEMPOTENCY_KEY_REUSED");

            // The second request had no effect at all.
            assertThat(countApplications()).isEqualTo(1);
        }

        @Test
        @DisplayName("formatting differences in an identical request are not a conflict")
        void whitespaceAndKeyOrderDoNotCauseAConflict() {
            String key = "idem-" + UUID.randomUUID();
            Map<String, Object> body = validApplicationRequest();

            ResponseEntity<Map> first = post("/v1/applications", body, key);

            // Same values, different insertion order. A client retrying through a
            // different HTTP library will serialise its JSON differently, and that
            // must not read as a different request.
            Map<String, Object> reordered = new java.util.LinkedHashMap<>();
            reordered.put("loan", body.get("loan"));
            reordered.put("applicant", body.get("applicant"));
            ResponseEntity<Map> second = post("/v1/applications", reordered, key);

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(second.getBody().get("applicationId"))
                    .isEqualTo(first.getBody().get("applicationId"));
        }

        @Test
        @DisplayName("concurrent requests with one key create exactly one application")
        void concurrentRequestsWithOneKeyCreateOneApplication() throws Exception {
            String key = "idem-" + UUID.randomUUID();
            Map<String, Object> body = validApplicationRequest();

            AtomicInteger created = new AtomicInteger();
            AtomicInteger conflicted = new AtomicInteger();

            try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
                List<Callable<Void>> calls = java.util.stream.IntStream.range(0, 4)
                        .<Callable<Void>>mapToObj(i -> () -> {
                            ResponseEntity<Map> response = post("/v1/applications", body, key);
                            if (response.getStatusCode() == HttpStatus.CREATED) {
                                created.incrementAndGet();
                            } else {
                                conflicted.incrementAndGet();
                            }
                            return null;
                        })
                        .toList();
                for (Future<Void> future : executor.invokeAll(calls)) {
                    future.get();
                }
            }

            // Whether a racing caller is served a replay or told to retry, the
            // invariant is the same: one key, one application.
            assertThat(created.get() + conflicted.get()).isEqualTo(4);
            assertThat(countApplications()).isEqualTo(1);
        }

        @Test
        @DisplayName("submission is idempotent, so a client retry cannot submit twice")
        void submissionIsIdempotent() {
            String applicationId = createApplicationAndMarkDocumentsClean();
            String key = "idem-" + UUID.randomUUID();

            ResponseEntity<Map> first = post("/v1/applications/" + applicationId + "/submit", null, key);
            ResponseEntity<Map> second = post("/v1/applications/" + applicationId + "/submit", null, key);

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(second.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");

            // One submission event, not two.
            assertThat(jdbc.queryForObject(
                            "SELECT count(*) FROM application.outbox_event WHERE event_type = 'application.submitted'",
                            Long.class))
                    .isEqualTo(1L);
        }
    }

    @Nested
    @DisplayName("error responses")
    class ErrorResponses {

        @Test
        @DisplayName("a validation failure names the field but never echoes its value")
        void validationErrorsDoNotEchoTheRejectedValue() {
            Map<String, Object> body = validApplicationRequest();
            // Contains a space, so it is genuinely invalid, while still embedding
            // the marker the assertion below looks for. A trailing "-bad" would
            // not do: hyphens are legal in a domain, so the value would be
            // accepted and the test would prove nothing.
            ((Map<String, Object>) body.get("applicant"))
                    .put("emailAddress", "invalid address " + SensitiveMarkers.EMAIL_ADDRESS);

            ResponseEntity<String> response = postRaw("/v1/applications", body, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody())
                    .contains("REQUEST_VALIDATION_FAILED")
                    .contains("emailAddress")
                    // The offending value is personal data and must not come back.
                    .doesNotContain(SensitiveMarkers.EMAIL_ADDRESS);
        }

        @Test
        @DisplayName("an unknown application is a 404 that reveals nothing about storage")
        void unknownApplicationReturnsASafeNotFound() {
            ResponseEntity<String> response = getRaw("/v1/applications/" + UUID.randomUUID());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody())
                    .contains("APPLICATION_NOT_FOUND")
                    .contains("correlationId")
                    .doesNotContain("Exception")
                    .doesNotContain("org.springframework")
                    .doesNotContain("select ")
                    .doesNotContain("loan_application");
        }

        @Test
        @DisplayName("a malformed identifier is a 400, not a 500")
        void malformedIdentifierIsAClientError() {
            ResponseEntity<String> response = getRaw("/v1/applications/not-a-uuid");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).contains("INVALID_REQUEST_VALUE");
        }

        @Test
        @DisplayName("submitting without mandatory documents reports which categories are missing")
        void missingDocumentsReportsTheCategories() {
            String applicationId = createApplication();

            ResponseEntity<String> response = postRaw("/v1/applications/" + applicationId + "/submit", null, null);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(response.getBody())
                    .contains("MANDATORY_DOCUMENTS_MISSING")
                    .contains("PROOF_OF_IDENTITY")
                    .contains("PROOF_OF_INCOME");
        }

        @Test
        @DisplayName("every error response carries the correlation identifier the client supplied")
        void errorsCarryTheSuppliedCorrelationId() {
            String correlationId = "client-correlation-0001";

            ResponseEntity<String> response = client.get()
                    .uri("/v1/applications/" + UUID.randomUUID())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + applicantToken())
                    .header("X-Correlation-Id", correlationId)
                    .retrieve()
                    .toEntity(String.class);

            assertThat(response.getBody()).contains(correlationId);
            assertThat(response.getHeaders().getFirst("X-Correlation-Id")).isEqualTo(correlationId);
        }
    }

    @Nested
    @DisplayName("authorisation")
    class Authorisation {

        @Test
        @DisplayName("a request without a token is refused")
        void anonymousRequestsAreRefused() {
            ResponseEntity<String> response = client.get()
                    .uri("/v1/applications/" + UUID.randomUUID())
                    .retrieve()
                    .toEntity(String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("an expired token is refused")
        void expiredTokensAreRefused() {
            String expired = jwtIssuer().mintExpiredToken("test-client", AUDIENCE, "applications:read");

            ResponseEntity<String> response = client.get()
                    .uri("/v1/applications/" + UUID.randomUUID())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + expired)
                    .retrieve()
                    .toEntity(String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("a correctly signed token for a different audience is refused")
        void tokensForAnotherAudienceAreRefused() {
            // Signed by the same trusted issuer and perfectly valid — for another
            // API. Without audience validation this call would succeed, which is
            // why the check exists.
            String wrongAudience =
                    jwtIssuer().mintToken("test-client", "some-other-api", "applications:read");

            ResponseEntity<String> response = client.get()
                    .uri("/v1/applications/" + UUID.randomUUID())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrongAudience)
                    .retrieve()
                    .toEntity(String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("a partner client cannot reach the manual-review queue")
        void applicantScopesCannotReachManualReview() {
            ResponseEntity<String> response = client.get()
                    .uri("/v1/manual-review/tasks")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + applicantToken())
                    .retrieve()
                    .toEntity(String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("liveness and readiness are reachable without a token, as the kubelet needs")
        void probesAreAnonymous() {
            assertThat(client.get().uri("/actuator/health/liveness").retrieve().toEntity(String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(client.get().uri("/actuator/health/readiness").retrieve().toEntity(String.class).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("the API never returns applicant personal data, and never logs it")
    void personalDataNeitherReturnedNorLogged() {
        Map<String, Object> body = validApplicationRequest();
        Map<String, Object> applicant = (Map<String, Object>) body.get("applicant");
        applicant.put("givenName", SensitiveMarkers.GIVEN_NAME);
        applicant.put("familyName", SensitiveMarkers.FAMILY_NAME);
        applicant.put("emailAddress", SensitiveMarkers.EMAIL_ADDRESS);
        applicant.put("dateOfBirth", SensitiveMarkers.DATE_OF_BIRTH);

        try (CapturedLogs logs = CapturedLogs.ofPlatform()) {
            ResponseEntity<String> created = postRaw("/v1/applications", body, null);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            String applicationId = extractApplicationId(created.getBody());
            ResponseEntity<String> fetched = getRaw("/v1/applications/" + applicationId);

            // The representation identifies the applicant by pseudonym and
            // initials. It never returns the values that were supplied.
            assertThat(created.getBody())
                    .doesNotContain(SensitiveMarkers.GIVEN_NAME)
                    .doesNotContain(SensitiveMarkers.FAMILY_NAME)
                    .doesNotContain(SensitiveMarkers.EMAIL_ADDRESS)
                    .doesNotContain(SensitiveMarkers.DATE_OF_BIRTH);
            assertThat(fetched.getBody())
                    .contains("applicantReference")
                    .doesNotContain(SensitiveMarkers.GIVEN_NAME)
                    .doesNotContain(SensitiveMarkers.FAMILY_NAME)
                    .doesNotContain(SensitiveMarkers.EMAIL_ADDRESS);

            // Nothing reached the logging pipeline either.
            for (String marker : SensitiveMarkers.all()) {
                assertThat(logs.all())
                        .withFailMessage("Sensitive marker '%s' reached the log pipeline.", marker)
                        .doesNotContain(marker);
            }
        }

        // The values are of course stored: this service is their system of record.
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM application.loan_application WHERE applicant_given_name = ?",
                        Long.class,
                        SensitiveMarkers.GIVEN_NAME))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("the pseudonymous reference is stable for the same applicant across applications")
    void applicantReferenceIsStableAcrossApplications() {
        ResponseEntity<Map> first = post("/v1/applications", validApplicationRequest(), null);
        ResponseEntity<Map> second = post("/v1/applications", validApplicationRequest(), null);

        assertThat(first.getBody().get("applicationId")).isNotEqualTo(second.getBody().get("applicationId"));
        // Stability is what lets fraud and AML checks correlate applications
        // without any downstream service holding personal data.
        assertThat(first.getBody().get("applicantReference"))
                .isEqualTo(second.getBody().get("applicantReference"));
    }

    // --- helpers -------------------------------------------------------------

    private Map<String, Object> validApplicationRequest() {
        Map<String, Object> applicant = new java.util.LinkedHashMap<>();
        applicant.put("givenName", "Test");
        applicant.put("familyName", "Applicant");
        applicant.put("emailAddress", "test.applicant@example.com");
        applicant.put("dateOfBirth", "1990-04-17");
        applicant.put("residenceCountry", "DE");

        Map<String, Object> loan = new java.util.LinkedHashMap<>();
        loan.put("amountMinorUnits", 1_250_000L);
        loan.put("currency", "EUR");
        loan.put("termMonths", 48);
        loan.put("purpose", "HOME_IMPROVEMENT");
        loan.put("declaredAnnualIncomeMinorUnits", 4_500_000L);
        loan.put("productCode", "PL-STD-01");

        Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("applicant", applicant);
        request.put("loan", loan);
        return request;
    }

    private String createApplication() {
        return (String) post("/v1/applications", validApplicationRequest(), null)
                .getBody()
                .get("applicationId");
    }

    private String createApplicationAndMarkDocumentsClean() {
        String applicationId = createApplication();
        SyntheticIntegrationData.markMandatoryDocumentsClean(
                jdbc, com.example.los.application.domain.model.ApplicationId.of(applicationId));
        return applicationId;
    }

    private ResponseEntity<Map> post(String path, Object body, String idempotencyKey) {
        var spec = client.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + applicantToken());
        if (idempotencyKey != null) {
            spec = spec.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().toEntity(Map.class);
    }

    private ResponseEntity<String> postRaw(String path, Object body, String idempotencyKey) {
        var spec = client.post().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + applicantToken());
        if (idempotencyKey != null) {
            spec = spec.header("Idempotency-Key", idempotencyKey);
        }
        if (body != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(body);
        }
        return spec.retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> getRaw(String path) {
        return client.get()
                .uri(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + applicantToken())
                .retrieve()
                .toEntity(String.class);
    }

    private String extractApplicationId(String json) {
        int start = json.indexOf("\"applicationId\":\"") + "\"applicationId\":\"".length();
        return json.substring(start, json.indexOf('"', start));
    }

    private long countApplications() {
        return jdbc.queryForObject("SELECT count(*) FROM application.loan_application", Long.class);
    }
}
