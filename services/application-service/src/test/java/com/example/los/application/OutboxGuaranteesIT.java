package com.example.los.application;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.los.application.adapter.out.messaging.OutboxPublisher;
import com.example.los.application.domain.exception.ProductRuleViolationException;
import com.example.los.application.domain.model.ApplicantDetails;
import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.ApplicationStatus;
import com.example.los.application.domain.model.LoanApplication;
import com.example.los.application.usecase.port.ConcurrentModificationConflict;
import com.example.los.application.usecase.service.ManageApplicationDraftUseCase;
import com.example.los.application.usecase.service.SubmitApplicationUseCase;
import com.example.los.events.EventTypes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Proves the transactional-outbox guarantees against real PostgreSQL and Kafka.
 *
 * <p>These are the tests that justify the design. Each one corresponds to a
 * failure mode the outbox pattern exists to eliminate, and each would pass
 * trivially against mocks while the real system lost data.
 */
class OutboxGuaranteesIT extends AbstractIntegrationTest {

    @Autowired
    private ManageApplicationDraftUseCase drafts;

    @Autowired
    private SubmitApplicationUseCase submissions;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDatabase() {
        resetDatabase();
    }

    @Test
    @DisplayName("an event is written in the same transaction as the state change, so it cannot be lost")
    void eventIsWrittenAtomicallyWithTheStateChange() {
        LoanApplication application = createDraftWithCleanDocuments();

        submissions.submit(application.id(), "corr-atomic-1");

        // The rows are present the instant the transaction commits, before the
        // publisher has run at all. There is no window in which the application
        // is submitted but nothing records that it was.
        List<Map<String, Object>> outbox = outboxRows(application.id());
        assertThat(outbox)
                .extracting(row -> row.get("event_type"))
                .contains(EventTypes.APPLICATION_SUBMITTED, EventTypes.APPLICATION_STATUS_CHANGED);
        assertThat(outbox).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("PENDING"));
    }

    @Test
    @DisplayName("a rolled-back transaction leaves no event behind")
    void rolledBackTransactionPublishesNothing() {
        // A draft that violates a product rule cannot be created at all, so this
        // exercises the rollback path with the state change already half applied.
        ApplicantDetails ineligible = SyntheticIntegrationData.applicantResidentIn("US");

        Throwable failure = catchThrowable(() ->
                drafts.createDraft(ineligible, SyntheticIntegrationData.request(), "corr-rollback-1"));

        assertThat(failure).isInstanceOf(ProductRuleViolationException.class);
        assertThat(countOutboxRows()).isZero();
        assertThat(countApplications()).isZero();
    }

    @Test
    @DisplayName("events reach Kafka and are marked published only after the broker acknowledges")
    void publisherDrainsTheOutboxToKafka() {
        LoanApplication application = createDraftWithCleanDocuments();
        submissions.submit(application.id(), "corr-publish-1");

        int published = publisher.publishBatch();

        assertThat(published).isPositive();
        assertThat(outboxRows(application.id()))
                .allSatisfy(row -> {
                    assertThat(row.get("status")).isEqualTo("PUBLISHED");
                    assertThat(row.get("published_at")).isNotNull();
                });
    }

    @Test
    @DisplayName("a broker outage leaves events in the outbox, and publication resumes on recovery")
    void brokerOutageDoesNotLoseEvents() {
        LoanApplication application = createDraftWithCleanDocuments();
        submissions.submit(application.id(), "corr-outage-1");
        int pendingBefore = outboxRows(application.id()).size();
        assertThat(pendingBefore).isPositive();

        // Simulates the broker being unreachable by pointing the publisher's
        // template at a port nothing is listening on. Stopping the shared
        // container would break every other test class in this JVM.
        KafkaTemplate<String, String> workingTemplate =
                (KafkaTemplate<String, String>) ReflectionTestUtils.getField(publisher, "kafka");
        ReflectionTestUtils.setField(publisher, "kafka", unreachableKafkaTemplate());

        int publishedDuringOutage = publisher.publishBatch();

        assertThat(publishedDuringOutage).isZero();
        // Nothing was lost, nothing was marked published, and every row is
        // scheduled for another attempt.
        assertThat(outboxRows(application.id()))
                .hasSize(pendingBefore)
                .allSatisfy(row -> {
                    assertThat(row.get("status")).isEqualTo("PENDING");
                    assertThat((Integer) row.get("attempts")).isEqualTo(1);
                    assertThat(row.get("last_error_code")).isEqualTo("BROKER_UNAVAILABLE");
                });

        // The broker returns.
        ReflectionTestUtils.setField(publisher, "kafka", workingTemplate);
        awaitSuccessfulDrain(application.id(), pendingBefore);
    }

    @Test
    @DisplayName("an event that exhausts its retry budget is parked, never silently dropped")
    void exhaustedRetryBudgetParksTheEventForTheRunbook() {
        LoanApplication application = createDraftWithCleanDocuments();
        submissions.submit(application.id(), "corr-exhaust-1");

        KafkaTemplate<String, String> workingTemplate =
                (KafkaTemplate<String, String>) ReflectionTestUtils.getField(publisher, "kafka");
        ReflectionTestUtils.setField(publisher, "kafka", unreachableKafkaTemplate());

        // max-attempts is 3 in the integration-test profile.
        for (int attempt = 0; attempt < 4; attempt++) {
            jdbcTemplate.update(
                    "UPDATE application.outbox_event SET next_attempt_at = now() - interval '1 minute'");
            publisher.publishBatch();
        }

        ReflectionTestUtils.setField(publisher, "kafka", workingTemplate);

        assertThat(outboxRows(application.id()))
                .allSatisfy(row -> {
                    // FAILED, not deleted: the row is the evidence the replay
                    // runbook works from, and deleting it would lose the event.
                    assertThat(row.get("status")).isEqualTo("FAILED");
                    assertThat(row.get("last_error_code")).isEqualTo("PUBLISH_RETRY_BUDGET_EXHAUSTED");
                });
    }

    @Test
    @DisplayName("concurrent publishers take disjoint batches and never publish an event twice")
    void concurrentPublishersDoNotOverlap() throws Exception {
        for (int i = 0; i < 5; i++) {
            LoanApplication application = createDraftWithCleanDocuments();
            submissions.submit(application.id(), "corr-concurrent-" + i);
        }
        long totalPending = countOutboxRows();
        assertThat(totalPending).isPositive();

        // Two publishers racing. FOR UPDATE SKIP LOCKED means neither blocks and
        // neither sees a row the other claimed, so the totals add up exactly.
        AtomicInteger total = new AtomicInteger();
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Integer>> tasks = List.of(publisher::publishBatch, publisher::publishBatch);
            for (Future<Integer> result : executor.invokeAll(tasks)) {
                total.addAndGet(result.get());
            }
        }

        assertThat(total.get()).isEqualTo((int) totalPending);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM application.outbox_event WHERE status = 'PENDING'", Long.class))
                .isZero();
    }

    @Test
    @DisplayName("two concurrent submissions of one application produce exactly one transition")
    void concurrentSubmissionsProduceOneTransition() throws Exception {
        LoanApplication application = createDraftWithCleanDocuments();
        ApplicationId id = application.id();

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger conflicted = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Callable<Void>> submits = List.of(
                    submitCountingOutcome(id, "corr-race-a", succeeded, conflicted),
                    submitCountingOutcome(id, "corr-race-b", succeeded, conflicted));
            for (Future<Void> result : executor.invokeAll(submits)) {
                result.get();
            }
        }

        // Optimistic locking on the aggregate is what makes this safe: both
        // transactions read the same version and only one can write the next.
        assertThat(succeeded.get()).isEqualTo(1);
        assertThat(conflicted.get()).isEqualTo(1);

        assertThat(applicationStatus(id))
                .isIn(ApplicationStatus.CHECKS_IN_PROGRESS.name(), ApplicationStatus.REJECTED.name());

        // Exactly one submission event, not two.
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM application.outbox_event WHERE aggregate_id = ? AND event_type = ?",
                        Long.class,
                        id.toString(),
                        EventTypes.APPLICATION_SUBMITTED))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("every event carries the correlation identifier and a causal chain")
    void eventsCarryCorrelationAndCausation() {
        LoanApplication application = createDraftWithCleanDocuments();
        submissions.submit(application.id(), "corr-chain-1");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                """
                SELECT id, correlation_id, causation_id, aggregate_version
                FROM application.outbox_event
                WHERE aggregate_id = ?
                ORDER BY created_at, aggregate_version
                """,
                application.id().toString());

        assertThat(rows).allSatisfy(row -> assertThat(row.get("correlation_id")).isEqualTo("corr-chain-1"));

        // The first event of the batch starts the chain; every later one names
        // its predecessor, which is what lets an operator reconstruct order.
        assertThat(rows.getFirst().get("causation_id")).isNull();
        assertThat(rows.stream().skip(1)).allSatisfy(row -> assertThat(row.get("causation_id")).isNotNull());
    }

    @Test
    @DisplayName("the partition key is always the application identifier, so events stay ordered per application")
    void partitionKeyIsTheApplicationIdentifier() {
        LoanApplication application = createDraftWithCleanDocuments();
        submissions.submit(application.id(), "corr-partition-1");

        assertThat(jdbcTemplate.queryForList(
                        "SELECT partition_key FROM application.outbox_event WHERE aggregate_id = ?",
                        String.class,
                        application.id().toString()))
                .isNotEmpty()
                .allSatisfy(key -> assertThat(key).isEqualTo(application.id().toString()));
    }

    // --- helpers -------------------------------------------------------------

    private Callable<Void> submitCountingOutcome(
            ApplicationId id, String correlationId, AtomicInteger succeeded, AtomicInteger conflicted) {
        return () -> {
            try {
                submissions.submit(id, correlationId);
                succeeded.incrementAndGet();
            } catch (ConcurrentModificationConflict | org.springframework.dao.DataIntegrityViolationException e) {
                conflicted.incrementAndGet();
            } catch (com.example.los.application.domain.exception.IllegalStateTransitionException e) {
                // The loser may also be refused by the state machine if it read
                // the aggregate after the winner committed. Either way it did not
                // produce a second transition, which is what this test asserts.
                conflicted.incrementAndGet();
            }
            return null;
        };
    }

    private LoanApplication createDraftWithCleanDocuments() {
        LoanApplication application = drafts.createDraft(
                SyntheticIntegrationData.applicant(), SyntheticIntegrationData.request(), "corr-setup");
        SyntheticIntegrationData.markMandatoryDocumentsClean(jdbcTemplate, application.id());
        return application;
    }

    private void awaitSuccessfulDrain(ApplicationId id, int expectedRows) {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    jdbcTemplate.update(
                            "UPDATE application.outbox_event SET next_attempt_at = now() - interval '1 minute' "
                                    + "WHERE status = 'PENDING'");
                    publisher.publishBatch();
                    assertThat(outboxRows(id))
                            .hasSize(expectedRows)
                            .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("PUBLISHED"));
                });
    }

    private KafkaTemplate<String, String> unreachableKafkaTemplate() {
        // Port 1 is reserved and nothing can bind it, so every send fails fast.
        var producerFactory = new org.springframework.kafka.core.DefaultKafkaProducerFactory<String, String>(Map.of(
                org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:1",
                org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                        org.apache.kafka.common.serialization.StringSerializer.class,
                org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                        org.apache.kafka.common.serialization.StringSerializer.class,
                org.apache.kafka.clients.producer.ProducerConfig.MAX_BLOCK_MS_CONFIG, 1000,
                org.apache.kafka.clients.producer.ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2000,
                org.apache.kafka.clients.producer.ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000));
        return new KafkaTemplate<>(producerFactory);
    }

    private List<Map<String, Object>> outboxRows(ApplicationId id) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM application.outbox_event WHERE aggregate_id = ? ORDER BY created_at, aggregate_version",
                id.toString());
    }

    private long countOutboxRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM application.outbox_event", Long.class);
    }

    private long countApplications() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM application.loan_application", Long.class);
    }

    private String applicationStatus(ApplicationId id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM application.loan_application WHERE id = ?", String.class, id.value());
    }
}
