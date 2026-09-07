package com.example.los.workflow.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.example.los.events.vocabulary.CheckOutcome;
import com.example.los.events.vocabulary.CheckType;
import com.example.los.events.vocabulary.Recommendation;
import com.example.los.workflow.domain.event.CheckCompleted;
import com.example.los.workflow.domain.event.WorkflowCompleted;
import com.example.los.workflow.domain.event.WorkflowDomainEvent;
import com.example.los.workflow.domain.event.WorkflowFailed;
import com.example.los.workflow.domain.event.WorkflowStarted;

/**
 * A durable assessment of one loan application.
 *
 * <p>This is the platform's workflow engine, and it is deliberately a state
 * machine persisted in PostgreSQL rather than a chain of synchronous HTTP calls.
 * The difference matters:
 *
 * <ul>
 *   <li>A chain of calls holds its progress in a thread's stack. If the pod is
 *       restarted mid-assessment — a deployment, a node drain, an OOM kill — that
 *       progress is gone, and the applicant is left in limbo with no record of
 *       which checks already ran.
 *   <li>This instance holds its progress in a row. A restart loses nothing: the
 *       next poll picks the workflow up exactly where it was, and a check that
 *       already succeeded is never re-run against an external system that may
 *       charge per call.
 * </ul>
 *
 * <p>The same property is what makes retries, deadlines and manual replay
 * possible at all: they are all just changes to a persisted schedule.
 *
 * <p>Framework-free by design. Persistence, messaging and the external checks
 * themselves all live behind ports in the layers above.
 */
public final class WorkflowInstance {

    private final WorkflowId id;
    private final String applicationId;
    private final String applicantReference;
    private final AssessmentInputs inputs;
    private final Instant startedAt;

    private WorkflowStatus status;
    private final Map<CheckType, CheckState> checks;
    private Recommendation recommendation;
    private String reasonCode;
    private Instant completedAt;
    private long version;

    private final List<WorkflowDomainEvent> pendingEvents = new ArrayList<>();

    private WorkflowInstance(
            WorkflowId id,
            String applicationId,
            String applicantReference,
            AssessmentInputs inputs,
            WorkflowStatus status,
            Map<CheckType, CheckState> checks,
            Recommendation recommendation,
            String reasonCode,
            Instant startedAt,
            Instant completedAt,
            long version) {
        this.id = id;
        this.applicationId = applicationId;
        this.applicantReference = applicantReference;
        this.inputs = inputs;
        this.status = status;
        this.checks = new EnumMap<>(checks);
        this.recommendation = recommendation;
        this.reasonCode = reasonCode;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.version = version;
    }

    /**
     * Starts an assessment with every check scheduled to run immediately.
     *
     * <p>All four checks are scheduled up front rather than sequenced, because
     * they are independent: KYC does not depend on the credit score, and running
     * them one after another would multiply the slowest external system's latency
     * by four for no benefit.
     */
    public static WorkflowInstance start(
            WorkflowId id, String applicationId, String applicantReference, AssessmentInputs inputs, Instant now) {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(inputs, "inputs must not be null");

        Map<CheckType, CheckState> checks = new EnumMap<>(CheckType.class);
        for (CheckType type : CheckType.values()) {
            checks.put(type, CheckState.scheduled(type, now));
        }

        WorkflowInstance instance = new WorkflowInstance(
                id,
                applicationId,
                applicantReference,
                inputs,
                WorkflowStatus.RUNNING,
                checks,
                null,
                null,
                now,
                null,
                1L);
        instance.pendingEvents.add(new WorkflowStarted(id, applicationId, List.copyOf(checks.keySet()), 1L, now));
        return instance;
    }

    /** Rebuilds an instance from storage. Raises no events. */
    public static WorkflowInstance reconstitute(
            WorkflowId id,
            String applicationId,
            String applicantReference,
            AssessmentInputs inputs,
            WorkflowStatus status,
            Map<CheckType, CheckState> checks,
            Recommendation recommendation,
            String reasonCode,
            Instant startedAt,
            Instant completedAt,
            long version) {
        return new WorkflowInstance(
                id,
                applicationId,
                applicantReference,
                inputs,
                status,
                checks,
                recommendation,
                reasonCode,
                startedAt,
                completedAt,
                version);
    }

    /**
     * Records the outcome of one check.
     *
     * <p>A definitive outcome — passed, failed or inconclusive — is terminal for
     * that check. It is never retried, because the external system answered: a
     * failed AML check is a business fact, and retrying it would eventually
     * produce a different answer only by accident.
     */
    public void recordCheckOutcome(CheckType type, CheckOutcome outcome, String checkReasonCode, Integer score, Instant now) {
        requireRunning();
        CheckState current = requireCheck(type);
        checks.put(type, current.completed(outcome, checkReasonCode, score, now));
        version++;
        pendingEvents.add(new CheckCompleted(
                id, applicationId, type, outcome, checkReasonCode, score, current.attempts() + 1, version, now));
    }

    /**
     * Records that a check attempt failed for a technical reason and schedules
     * the next attempt.
     *
     * <p>A transient failure is not an answer. The external system did not say
     * "no"; it said nothing. Retrying is therefore correct, and the retry state
     * lives in the row so it survives a restart.
     */
    public void recordTransientFailure(CheckType type, String errorCode, Duration backoff, Instant now) {
        requireRunning();
        CheckState current = requireCheck(type);
        checks.put(type, current.failedAttempt(errorCode, now.plus(backoff), now));
        version++;
    }

    /**
     * Gives up on a check whose retry budget is exhausted.
     *
     * <p>This fails the whole workflow rather than deciding without the check. An
     * assessment missing its AML result is not an assessment, and quietly
     * approving on partial evidence is precisely the failure mode a lending
     * platform must not have.
     */
    public void abandonCheck(CheckType type, String errorCode, Instant now) {
        requireRunning();
        CheckState current = requireCheck(type);
        checks.put(type, current.abandoned(errorCode, now));
        this.status = WorkflowStatus.FAILED;
        this.reasonCode = errorCode;
        this.completedAt = now;
        version++;
        pendingEvents.add(new WorkflowFailed(id, applicationId, type, errorCode, current.attempts() + 1, version, now));
    }

    /**
     * Evaluates the decision once every check has answered.
     *
     * @return the recommendation, or empty when checks are still outstanding
     */
    public Optional<Recommendation> concludeIfReady(AssessmentPolicy policy, Instant now) {
        if (status != WorkflowStatus.RUNNING || !allChecksComplete()) {
            return Optional.empty();
        }

        AssessmentPolicy.Outcome outcome = policy.evaluate(checks, inputs);

        this.recommendation = outcome.recommendation();
        this.reasonCode = outcome.reasonCode();
        this.status = WorkflowStatus.COMPLETED;
        this.completedAt = now;
        version++;
        pendingEvents.add(
                new WorkflowCompleted(id, applicationId, outcome.recommendation(), outcome.reasonCode(), version, now));

        return Optional.of(outcome.recommendation());
    }

    /** Checks that are due to run at or before the given instant. */
    public List<CheckState> checksDueAt(Instant now) {
        return checks.values().stream().filter(check -> check.isDueAt(now)).toList();
    }

    public boolean allChecksComplete() {
        return checks.values().stream().allMatch(CheckState::isComplete);
    }

    private void requireRunning() {
        if (status != WorkflowStatus.RUNNING) {
            throw new IllegalWorkflowStateException(id, status);
        }
    }

    private CheckState requireCheck(CheckType type) {
        CheckState state = checks.get(type);
        if (state == null) {
            throw new IllegalArgumentException("Workflow does not include check " + type);
        }
        if (state.isComplete()) {
            throw new IllegalWorkflowStateException(id, status);
        }
        return state;
    }

    public List<WorkflowDomainEvent> drainPendingEvents() {
        List<WorkflowDomainEvent> drained = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return drained;
    }

    public List<WorkflowDomainEvent> pendingEvents() {
        return List.copyOf(pendingEvents);
    }

    public WorkflowId id() {
        return id;
    }

    public String applicationId() {
        return applicationId;
    }

    public String applicantReference() {
        return applicantReference;
    }

    public AssessmentInputs inputs() {
        return inputs;
    }

    public WorkflowStatus status() {
        return status;
    }

    public Map<CheckType, CheckState> checks() {
        return Map.copyOf(checks);
    }

    public Optional<Recommendation> recommendation() {
        return Optional.ofNullable(recommendation);
    }

    public String reasonCode() {
        return reasonCode;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant completedAt() {
        return completedAt;
    }

    public long version() {
        return version;
    }

    @Override
    public String toString() {
        return "WorkflowInstance[id=" + id + ", applicationId=" + applicationId + ", status=" + status + ", version="
                + version + "]";
    }
}
