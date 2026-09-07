package com.example.los.audit;

import java.time.Instant;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.los.audit.domain.model.UnsafeAuditSummaryException;
import com.example.los.audit.usecase.service.RecordAuditEntryUseCase;
import com.example.los.testsupport.PlatformContainers;
import com.example.los.testsupport.SensitiveMarkers;
import com.example.los.testsupport.TestJwtIssuer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The audit trail against real PostgreSQL.
 *
 * <p>Covers the properties that only show up against a real database: the unique
 * constraint that makes the consumer idempotent, the behaviour under concurrent
 * writers, and end-to-end chain verification over rows that were actually
 * persisted and read back.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
class AuditTrailIT {

    private static final TestJwtIssuer JWT_ISSUER = TestJwtIssuer.start();

    @Autowired
    private RecordAuditEntryUseCase recording;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PlatformContainers.postgres()::getJdbcUrl);
        registry.add("spring.datasource.username", PlatformContainers.postgres()::getUsername);
        registry.add("spring.datasource.password", PlatformContainers.postgres()::getPassword);
        registry.add("spring.kafka.bootstrap-servers", PlatformContainers.kafka()::getBootstrapServers);
        registry.add("spring.kafka.security.protocol", () -> "PLAINTEXT");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", JWT_ISSUER::issuerUri);
        registry.add("los.security.accepted-audiences", () -> "los-test-api");
    }

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE TABLE audit.audit_record, audit.processed_event RESTART IDENTITY CASCADE");
    }

    @Test
    @DisplayName("events are recorded as a verifiable chain")
    void eventsFormAVerifiableChain() {
        String applicationId = UUID.randomUUID().toString();

        recording.record(entry(applicationId, "evt-1", "application.submitted", Map.of("status", "SUBMITTED")));
        recording.record(entry(applicationId, "evt-2", "workflow.started", Map.of("status", "RUNNING")));
        recording.record(entry(applicationId, "evt-3", "application.decision-recorded", Map.of("decision", "APPROVED")));

        RecordAuditEntryUseCase.ChainVerification verification = recording.verifyChain(applicationId);

        assertThat(verification.intact()).isTrue();
        assertThat(verification.recordCount()).isEqualTo(3);
        assertThat(verification.brokenAtSequence()).isNull();
    }

    @Test
    @DisplayName("editing a stored record is detected by verification")
    void tamperingWithAStoredRecordIsDetected() {
        String applicationId = UUID.randomUUID().toString();
        recording.record(entry(applicationId, "evt-1", "application.submitted", Map.of("status", "SUBMITTED")));
        recording.record(
                entry(applicationId, "evt-2", "application.decision-recorded", Map.of("decision", "REJECTED")));

        assertThat(recording.verifyChain(applicationId).intact()).isTrue();

        // Someone with database access flips a rejection into an approval,
        // leaving the hash alone because recomputing it would break the chain.
        jdbc.update(
                """
                UPDATE audit.audit_record
                SET summary = '{"decision":"APPROVED"}'::jsonb
                WHERE chain_key = ? AND sequence_number = 2
                """,
                applicationId);

        RecordAuditEntryUseCase.ChainVerification verification = recording.verifyChain(applicationId);

        assertThat(verification.intact()).isFalse();
        assertThat(verification.brokenAtSequence()).isEqualTo(2L);
    }

    @Test
    @DisplayName("deleting a record from the middle of a chain is detected")
    void deletingARecordIsDetected() {
        String applicationId = UUID.randomUUID().toString();
        recording.record(entry(applicationId, "evt-1", "application.submitted", Map.of("status", "SUBMITTED")));
        recording.record(entry(applicationId, "evt-2", "workflow.started", Map.of("status", "RUNNING")));
        recording.record(entry(applicationId, "evt-3", "workflow.completed", Map.of("recommendation", "APPROVE")));

        jdbc.update("DELETE FROM audit.audit_record WHERE chain_key = ? AND sequence_number = 2", applicationId);

        RecordAuditEntryUseCase.ChainVerification verification = recording.verifyChain(applicationId);

        assertThat(verification.intact()).isFalse();
        // The third record's own hash is fine; it is the link that broke.
        assertThat(verification.brokenAtSequence()).isEqualTo(3L);
    }

    @Test
    @DisplayName("a redelivered event does not append a second record")
    void duplicateEventIsIgnored() {
        String applicationId = UUID.randomUUID().toString();
        RecordAuditEntryUseCase.AuditEntry entry =
                entry(applicationId, "evt-1", "application.submitted", Map.of("status", "SUBMITTED"));

        assertThat(recording.record(entry)).isEqualTo(RecordAuditEntryUseCase.Outcome.RECORDED);
        assertThat(recording.record(entry)).isEqualTo(RecordAuditEntryUseCase.Outcome.DUPLICATE_IGNORED);

        // A second record would extend the chain with an entry an auditor could
        // not distinguish from a genuine repeated action.
        assertThat(recordCount(applicationId)).isEqualTo(1);
        assertThat(recording.verifyChain(applicationId).intact()).isTrue();
    }

    @Test
    @DisplayName("concurrent deliveries of one event append exactly one record")
    void concurrentDuplicatesAppendOnce() throws Exception {
        String applicationId = UUID.randomUUID().toString();
        RecordAuditEntryUseCase.AuditEntry entry =
                entry(applicationId, "evt-race", "application.submitted", Map.of("status", "SUBMITTED"));

        AtomicInteger recorded = new AtomicInteger();
        AtomicInteger ignored = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Void>> calls = java.util.stream.IntStream.range(0, 4)
                    .<Callable<Void>>mapToObj(i -> () -> {
                        try {
                            if (recording.record(entry) == RecordAuditEntryUseCase.Outcome.RECORDED) {
                                recorded.incrementAndGet();
                            } else {
                                ignored.incrementAndGet();
                            }
                        } catch (RuntimeException lostTheRace) {
                            // A writer that lost on the unique constraint. Its
                            // transaction rolled back, which is the correct
                            // outcome; it simply did not append.
                            ignored.incrementAndGet();
                        }
                        return null;
                    })
                    .toList();
            for (Future<Void> future : executor.invokeAll(calls)) {
                future.get();
            }
        }

        assertThat(recorded.get()).isEqualTo(1);
        assertThat(recordCount(applicationId)).isEqualTo(1);
    }

    @Test
    @DisplayName("chains for different applications are independent")
    void chainsAreIndependentPerApplication() {
        // A single global chain would serialise every audit write on the
        // platform. Per-application chains keep unrelated writes independent.
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();

        recording.record(entry(first, "evt-a1", "application.submitted", Map.of("status", "SUBMITTED")));
        recording.record(entry(second, "evt-b1", "application.submitted", Map.of("status", "SUBMITTED")));
        recording.record(entry(first, "evt-a2", "workflow.started", Map.of("status", "RUNNING")));

        assertThat(recordCount(first)).isEqualTo(2);
        assertThat(recordCount(second)).isEqualTo(1);
        assertThat(recording.verifyChain(first).intact()).isTrue();
        assertThat(recording.verifyChain(second).intact()).isTrue();

        // Each chain starts at sequence 1 in its own right.
        assertThat(sequenceNumbers(first)).containsExactly(1L, 2L);
        assertThat(sequenceNumbers(second)).containsExactly(1L);
    }

    @Test
    @DisplayName("a summary carrying personal data is refused and nothing is written")
    void personalDataIsRefused() {
        String applicationId = UUID.randomUUID().toString();

        assertThatThrownBy(() -> recording.record(new RecordAuditEntryUseCase.AuditEntry(
                        "evt-unsafe",
                        "application.submitted",
                        1,
                        "LoanApplication",
                        applicationId,
                        1L,
                        "APR-0000000000000000",
                        Map.of("applicantEmail", SensitiveMarkers.EMAIL_ADDRESS),
                        "corr-1",
                        Instant.now())))
                .isInstanceOf(UnsafeAuditSummaryException.class);

        // Refused outright rather than written with the field dropped: a record
        // silently missing the fact somebody tried to store is worse than none.
        assertThat(recordCount(applicationId)).isZero();
    }

    @Test
    @DisplayName("no personal data reaches the audit table")
    void auditTableContainsNoPersonalData() {
        String applicationId = UUID.randomUUID().toString();
        recording.record(entry(applicationId, "evt-1", "application.submitted", Map.of("status", "SUBMITTED")));
        recording.record(
                entry(applicationId, "evt-2", "application.decision-recorded", Map.of("decision", "APPROVED")));

        // Everything the table holds, as one string.
        String everything = String.join(
                "\n",
                jdbc.queryForList(
                        """
                        SELECT concat_ws(' ', chain_key, source_event_id, event_type, aggregate_type,
                                            aggregate_id, subject_reference, summary::text, correlation_id)
                        FROM audit.audit_record
                        """,
                        String.class));

        for (String marker : SensitiveMarkers.all()) {
            assertThat(everything)
                    .withFailMessage("Sensitive marker '%s' reached the audit table.", marker)
                    .doesNotContain(marker);
        }
    }

    @Test
    @DisplayName("verifying an application with no audit history reports an intact, empty chain")
    void emptyChainVerifies() {
        RecordAuditEntryUseCase.ChainVerification verification =
                recording.verifyChain(UUID.randomUUID().toString());

        assertThat(verification.intact()).isTrue();
        assertThat(verification.recordCount()).isZero();
    }

    // --- helpers -------------------------------------------------------------

    private static RecordAuditEntryUseCase.AuditEntry entry(
            String applicationId, String eventId, String eventType, Map<String, String> summary) {
        return new RecordAuditEntryUseCase.AuditEntry(
                eventId,
                eventType,
                1,
                "LoanApplication",
                applicationId,
                1L,
                "APR-0000000000000000",
                summary,
                "corr-" + applicationId,
                Instant.parse("2026-01-15T09:30:00Z"));
    }

    private long recordCount(String chainKey) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_record WHERE chain_key = ?", Long.class, chainKey);
    }

    private List<Long> sequenceNumbers(String chainKey) {
        return jdbc.queryForList(
                "SELECT sequence_number FROM audit.audit_record WHERE chain_key = ? ORDER BY sequence_number",
                Long.class,
                chainKey);
    }
}
