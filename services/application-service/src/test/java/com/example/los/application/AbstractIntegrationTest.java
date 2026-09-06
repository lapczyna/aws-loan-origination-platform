package com.example.los.application;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.los.testsupport.PlatformContainers;
import com.example.los.testsupport.TestJwtIssuer;

/**
 * Base class for integration tests: real PostgreSQL, real Kafka, real security.
 *
 * <p>Nothing here is mocked. Flyway runs the actual migrations against a real
 * PostgreSQL of the same major version the Terraform module provisions, the
 * outbox publisher talks to a real broker, and the resource server validates
 * tokens from a real OIDC issuer. Tests that mock the database prove that the
 * mocks agree with each other, not that {@code FOR UPDATE SKIP LOCKED} or a
 * partial unique index behaves as intended.
 *
 * <p>Containers and the issuer are shared across every test class in the JVM and
 * torn down when it exits. Isolation comes from {@link #resetDatabase()}, which
 * each test calls, rather than from a fresh container per class — the latter
 * would add roughly fifteen seconds per test class for no additional confidence.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integration-test")
public abstract class AbstractIntegrationTest {

    protected static final String AUDIENCE = "los-test-api";

    /**
     * Started once per JVM and stopped when it exits. Deliberately not tied to a
     * test class lifecycle: restarting the issuer per class would rotate the
     * signing key and cost a discovery round trip for no extra confidence.
     */
    private static final TestJwtIssuer JWT_ISSUER = TestJwtIssuer.start();
    private static final Path PEPPER_FILE = writeSyntheticPepper();

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PlatformContainers.postgres()::getJdbcUrl);
        registry.add("spring.datasource.username", PlatformContainers.postgres()::getUsername);
        registry.add("spring.datasource.password", PlatformContainers.postgres()::getPassword);

        registry.add("spring.kafka.bootstrap-servers", PlatformContainers.kafka()::getBootstrapServers);
        registry.add("spring.kafka.security.protocol", () -> "PLAINTEXT");

        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", JWT_ISSUER::issuerUri);
        registry.add("los.security.accepted-audiences", () -> AUDIENCE);
        registry.add("los.security.applicant-pepper-file", PEPPER_FILE::toString);

        // The publisher is driven explicitly by the tests that exercise it, so a
        // background scheduler cannot drain the outbox between an action and the
        // assertion about what the outbox contains.
        registry.add("los.outbox.poll-interval", () -> "PT1H");
        registry.add("los.idempotency.purge-interval", () -> "PT1H");
    }

    protected static TestJwtIssuer jwtIssuer() {
        return JWT_ISSUER;
    }

    /** A token with the scopes a partner client holds. */
    protected String applicantToken() {
        return JWT_ISSUER.mintToken(
                "test-client", AUDIENCE, "applications:read", "applications:write");
    }

    /** A token with the scopes an internal reviewer client holds. */
    protected String reviewerToken(String reviewerReference) {
        return JWT_ISSUER.mintToken(
                reviewerReference, AUDIENCE, "applications:read", "manual-review:read", "manual-review:decide");
    }

    /**
     * Empties every table between tests.
     *
     * <p>Truncating rather than recreating the schema keeps the migrations
     * applied once per JVM, which is the expensive part, while still giving each
     * test an empty database. {@code RESTART IDENTITY CASCADE} also resets
     * sequences, so a test cannot accidentally depend on an identifier another
     * test happened to leave behind.
     */
    protected void resetDatabase() {
        jdbcTemplate.execute(
                """
                TRUNCATE TABLE
                    application.outbox_event,
                    application.processed_event,
                    application.idempotency_key,
                    application.document_status,
                    application.application_version,
                    application.loan_application
                RESTART IDENTITY CASCADE
                """);
    }


    /**
     * Writes a synthetic pepper to a temporary file.
     *
     * <p>Generated per run into the JVM's temporary directory, never committed.
     * The service reads its pepper from a file in every environment, so the test
     * exercises the same code path a deployed pod does.
     */
    private static Path writeSyntheticPepper() {
        try {
            Path file = Files.createTempFile("los-test-pepper", ".txt");
            Files.writeString(
                    file, "synthetic-test-pepper-not-a-secret-0123456789abcdef", StandardCharsets.UTF_8);
            file.toFile().deleteOnExit();
            return file;
        } catch (IOException e) {
            throw new IllegalStateException("Could not write the synthetic test pepper", e);
        }
    }
}
