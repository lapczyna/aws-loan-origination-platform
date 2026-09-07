package com.example.los.workflow;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.example.los.events.EventTypes;
import com.example.los.events.vocabulary.CheckType;
import com.example.los.events.vocabulary.Recommendation;
import com.example.los.testsupport.PlatformContainers;
import com.example.los.workflow.adapter.out.checks.SimulatedScenario;
import com.example.los.workflow.domain.model.AssessmentInputs;
import com.example.los.workflow.domain.model.WorkflowId;
import com.example.los.workflow.domain.model.WorkflowStatus;
import com.example.los.workflow.usecase.port.WorkflowRepository;
import com.example.los.workflow.usecase.service.StartAssessmentUseCase;
import com.example.los.workflow.usecase.service.WorkflowScheduler;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assessment loop, end to end, against real PostgreSQL.
 *
 * <p>Drives the use cases directly rather than through Kafka: the broker's part
 * is already covered by the outbox tests, and driving it here would make every
 * scenario wait on delivery timing for no extra coverage of the workflow itself.
 * The scheduler, the lease, the retry schedule, the circuit breaker and the
 * decision policy are all exercised for real.
 */
@SpringBootTest
@ActiveProfiles("integration-test")
class WorkflowAssessmentIT {

    @Autowired
    private StartAssessmentUseCase startAssessment;

    @Autowired
    private WorkflowScheduler scheduler;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private WorkflowRepository workflows;

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PlatformContainers.postgres()::getJdbcUrl);
        registry.add("spring.datasource.username", PlatformContainers.postgres()::getUsername);
        registry.add("spring.datasource.password", PlatformContainers.postgres()::getPassword);
        registry.add("spring.kafka.bootstrap-servers", PlatformContainers.kafka()::getBootstrapServers);
        registry.add("spring.kafka.security.protocol", () -> "PLAINTEXT");

        // The tests drive the scheduler explicitly, so a background poll cannot
        // advance a workflow between an action and the assertion about it.
        registry.add("los.workflow.poll-interval", () -> "PT1H");
        registry.add("los.outbox.poll-interval", () -> "PT1H");
        // A short lease so a test can observe a leased workflow becoming due again.
        registry.add("los.workflow.lease", () -> "PT2S");
        registry.add("los.workflow.base-backoff", () -> "PT0.05S");
        registry.add("los.workflow.max-backoff", () -> "PT0.2S");
        registry.add("los.workflow.max-attempts", () -> "3");
        registry.add("los.checks.timeout", () -> "PT1S");
    }

    @BeforeEach
    void resetDatabase() {
        jdbc.execute(
                """
                TRUNCATE TABLE
                    workflow.outbox_event,
                    workflow.processed_event,
                    workflow.workflow_check,
                    workflow.workflow_instance
                RESTART IDENTITY CASCADE
                """);
    }

    @Test
    @DisplayName("a clean application is assessed and approved")
    void cleanApplicationIsApproved() {
        String applicationId = startFor(SimulatedScenario.SUCCESS, affordable());

        drainScheduler();

        assertThat(statusOf(applicationId)).isEqualTo(WorkflowStatus.COMPLETED.name());
        assertThat(recommendationOf(applicationId)).isEqualTo(Recommendation.APPROVE.name());

        // Every check answered exactly once.
        assertThat(checkOutcomes(applicationId))
                .hasSize(CheckType.values().length)
                .allSatisfy(row -> assertThat(row.get("outcome")).isEqualTo("PASSED"));

        // The decision was announced through the outbox, in the same transaction
        // that recorded it.
        assertThat(publishedEventTypes(applicationId))
                .contains(EventTypes.WORKFLOW_STARTED, EventTypes.WORKFLOW_CHECK_COMPLETED, EventTypes.WORKFLOW_COMPLETED);
    }

    @Test
    @DisplayName("a business rejection is recorded once and never retried")
    void businessRejectionIsNotRetried() {
        String applicationId = startFor(SimulatedScenario.BUSINESS_REJECTION, affordable());

        drainScheduler();

        assertThat(recommendationOf(applicationId)).isEqualTo(Recommendation.REJECT.name());
        // One attempt per check: the provider answered, so nothing was retried.
        assertThat(checkOutcomes(applicationId))
                .allSatisfy(row -> assertThat((Integer) row.get("attempts")).isEqualTo(1));
    }

    @Test
    @DisplayName("an inconclusive check routes the application to a human")
    void inconclusiveCheckGoesToManualReview() {
        String applicationId = startFor(SimulatedScenario.INCONCLUSIVE, affordable());

        drainScheduler();

        assertThat(recommendationOf(applicationId)).isEqualTo(Recommendation.MANUAL_REVIEW.name());
    }

    @Test
    @DisplayName("a transient failure is retried and the assessment completes")
    void transientFailureIsRetriedAndRecovers() {
        // The simulator fails the first attempt for every check and succeeds
        // afterwards, so this proves the retry actually works rather than merely
        // that a retry was scheduled.
        String applicationId = startFor(SimulatedScenario.TRANSIENT_FAILURE, affordable());

        drainScheduler();

        assertThat(statusOf(applicationId)).isEqualTo(WorkflowStatus.COMPLETED.name());
        assertThat(checkOutcomes(applicationId))
                .allSatisfy(row -> {
                    assertThat(row.get("outcome")).isEqualTo("PASSED");
                    // More than one attempt: the first failed, a later one worked.
                    assertThat((Integer) row.get("attempts")).isGreaterThan(1);
                });
    }

    @Test
    @DisplayName("a permanent failure abandons the assessment immediately, without burning retries")
    void permanentFailureIsNotRetried() {
        String applicationId = startFor(SimulatedScenario.PERMANENT_FAILURE, affordable());

        drainScheduler();

        assertThat(statusOf(applicationId)).isEqualTo(WorkflowStatus.FAILED.name());
        // FAILED, not REJECT: nothing was decided about the applicant.
        assertThat(recommendationOf(applicationId)).isNull();
        assertThat(publishedEventTypes(applicationId)).contains(EventTypes.WORKFLOW_FAILED);

        assertThat(checkOutcomes(applicationId))
                .filteredOn(row -> "ABANDONED".equals(row.get("reason_code")))
                .isNotEmpty()
                .allSatisfy(row -> assertThat((Integer) row.get("attempts")).isEqualTo(1));
    }

    @Test
    @DisplayName("a provider that never answers exhausts its budget and fails the assessment")
    void unresponsiveProviderExhaustsItsBudget() {
        // The timeout is what ends each attempt; the retry budget is what ends
        // the assessment. Concluding on the checks that did answer would be worse
        // than failing.
        String applicationId = startFor(SimulatedScenario.TIMEOUT, affordable());

        drainScheduler();

        assertThat(statusOf(applicationId)).isEqualTo(WorkflowStatus.FAILED.name());
        assertThat(recommendationOf(applicationId)).isNull();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT reason_code FROM workflow.workflow_instance WHERE application_id = ?
                        """,
                        String.class,
                        UUID.fromString(applicationId)))
                .isEqualTo("CHECK_RETRY_BUDGET_EXHAUSTED");
    }

    @Test
    @DisplayName("a redelivered submission event does not start a second assessment")
    void duplicateSubmissionEventIsIgnored() {
        String applicationId = UUID.randomUUID().toString();
        String eventId = UUID.randomUUID().toString();

        StartAssessmentUseCase.Outcome first =
                startAssessment.start(eventId, applicationId, "APR-SUCCESS0000001", affordable(), "corr-1");
        StartAssessmentUseCase.Outcome second =
                startAssessment.start(eventId, applicationId, "APR-SUCCESS0000001", affordable(), "corr-1");

        assertThat(first).isEqualTo(StartAssessmentUseCase.Outcome.STARTED);
        assertThat(second).isEqualTo(StartAssessmentUseCase.Outcome.DUPLICATE_EVENT_IGNORED);
        assertThat(workflowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a different event for an application already being assessed does not start a second one")
    void secondEventForTheSameApplicationIsIgnored() {
        // Not a duplicate delivery but a genuinely different event — a replayed
        // topic, or a resubmission after an operator intervention. Starting again
        // would call every provider a second time and could produce a
        // contradictory recommendation for one application.
        String applicationId = UUID.randomUUID().toString();

        startAssessment.start(UUID.randomUUID().toString(), applicationId, "APR-SUCCESS0000001", affordable(), "c1");
        StartAssessmentUseCase.Outcome second = startAssessment.start(
                UUID.randomUUID().toString(), applicationId, "APR-SUCCESS0000001", affordable(), "c2");

        assertThat(second).isEqualTo(StartAssessmentUseCase.Outcome.ALREADY_ASSESSING);
        assertThat(workflowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("assessment progress survives a restart, because it lives in the database")
    void progressIsDurable() {
        String applicationId = startFor(SimulatedScenario.SUCCESS, affordable());

        // One pass of the scheduler. Some checks have answered and the results
        // are committed rows, not state on a thread's stack.
        scheduler.advanceDueWorkflows();
        List<Map<String, Object>> afterFirstPass = checkOutcomes(applicationId);
        assertThat(afterFirstPass).isNotEmpty();

        // Nothing in memory carries over between these calls; everything the next
        // pass needs is read back from PostgreSQL.
        drainScheduler();

        assertThat(statusOf(applicationId)).isEqualTo(WorkflowStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("a leased workflow is invisible to another poller until the lease expires")
    void leasePreventsTwoReplicasTakingTheSameWorkflow() {
        startFor(SimulatedScenario.SUCCESS, affordable());
        Instant now = Instant.now();

        // Simulates two replicas polling at the same moment. The first leases the
        // workflow; the second finds nothing, because the lease pushed the next
        // attempt time into the future. This is what stops two replicas calling
        // the same provider for the same application -- a duplicated charge
        // against a bureau that bills per lookup, not merely a wasted call.
        List<WorkflowId> firstReplica = workflows.leaseDueWorkflows(now, Duration.ofMinutes(1), 50);
        List<WorkflowId> secondReplica = workflows.leaseDueWorkflows(now, Duration.ofMinutes(1), 50);

        assertThat(firstReplica).hasSize(1);
        assertThat(secondReplica).isEmpty();

        // Once the lease lapses the work is claimable again, so a replica that
        // died mid-batch delays an assessment rather than losing it.
        assertThat(workflows.leaseDueWorkflows(now.plusSeconds(120), Duration.ofMinutes(1), 50))
                .containsExactlyElementsOf(firstReplica);
    }

    // --- helpers -------------------------------------------------------------

    private static AssessmentInputs affordable() {
        return new AssessmentInputs(1_000_000L, "EUR", 48, "HOME_IMPROVEMENT", 5_000_000L, "PL-STD-01");
    }

    /**
     * Starts an assessment whose simulated providers exhibit the given scenario.
     *
     * <p>The scenario is encoded in the applicant reference, so the simulator
     * behaves deterministically for this synthetic applicant.
     */
    private String startFor(SimulatedScenario scenario, AssessmentInputs inputs) {
        String applicationId = UUID.randomUUID().toString();
        startAssessment.start(
                UUID.randomUUID().toString(),
                applicationId,
                "APR-" + scenario.name(),
                inputs,
                "corr-" + scenario.name());
        return applicationId;
    }

    /**
     * Runs the scheduler until every workflow reaches a terminal state.
     *
     * <p>Repeated polling rather than one call, because retries are scheduled
     * into the future and a single pass cannot complete a workflow that had to
     * back off. The lease is short in this profile so the retries become
     * claimable quickly.
     */
    private void drainScheduler() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    scheduler.advanceDueWorkflows();
                    assertThat(runningWorkflowCount())
                            .withFailMessage("Workflows are still running after repeated scheduler passes")
                            .isZero();
                });
    }

    private long runningWorkflowCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM workflow.workflow_instance WHERE status = 'RUNNING'", Long.class);
    }

    private long workflowCount() {
        return jdbc.queryForObject("SELECT count(*) FROM workflow.workflow_instance", Long.class);
    }

    private String statusOf(String applicationId) {
        return jdbc.queryForObject(
                "SELECT status FROM workflow.workflow_instance WHERE application_id = ?",
                String.class,
                UUID.fromString(applicationId));
    }

    private String recommendationOf(String applicationId) {
        return jdbc.queryForObject(
                "SELECT recommendation FROM workflow.workflow_instance WHERE application_id = ?",
                String.class,
                UUID.fromString(applicationId));
    }

    private List<Map<String, Object>> checkOutcomes(String applicationId) {
        return jdbc.queryForList(
                """
                SELECT c.* FROM workflow.workflow_check c
                JOIN workflow.workflow_instance i ON i.id = c.workflow_id
                WHERE i.application_id = ?
                """,
                UUID.fromString(applicationId));
    }

    private List<String> publishedEventTypes(String applicationId) {
        return jdbc.queryForList(
                "SELECT event_type FROM workflow.outbox_event WHERE partition_key = ?",
                String.class,
                applicationId);
    }
}
