package com.example.los.workflow.usecase.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.workflow.domain.model.AssessmentPolicy;
import com.example.los.workflow.domain.model.CheckState;
import com.example.los.workflow.domain.model.WorkflowId;
import com.example.los.workflow.domain.model.WorkflowInstance;
import com.example.los.workflow.domain.model.WorkflowReasonCodes;
import com.example.los.workflow.usecase.port.CheckExecutor;
import com.example.los.workflow.usecase.port.ExternalCheckPort;
import com.example.los.workflow.usecase.port.OutboxWriter;
import com.example.los.workflow.usecase.port.PermanentCheckFailure;
import com.example.los.workflow.usecase.port.TransientCheckFailure;
import com.example.los.workflow.usecase.port.WorkflowRepository;

/**
 * Advances one assessment: runs its due checks, then concludes it if it is ready.
 *
 * <h2>Failure handling</h2>
 *
 * <p>The three failure classes are treated completely differently, and conflating
 * them is the classic defect in this kind of code:
 *
 * <ul>
 *   <li><b>A business answer</b> — passed, failed or inconclusive — is recorded
 *       and the check is finished. A failed AML check is never retried; retrying
 *       it could only produce a different answer by accident.
 *   <li><b>A transient failure</b> is retried with exponential backoff and full
 *       jitter, up to a budget. The provider did not say no; it said nothing.
 *   <li><b>A permanent failure</b> abandons the check immediately. Retrying a
 *       malformed request or a revoked credential burns the budget and delays the
 *       applicant with no prospect of a different result.
 * </ul>
 *
 * <p>When a check exhausts its budget the whole assessment fails rather than
 * concluding on partial evidence. Approving a loan without the AML result because
 * the provider was unreachable is exactly the failure a lending platform must not
 * have.
 *
 * <h2>Why one transaction per workflow</h2>
 *
 * <p>A batch shares nothing. One workflow failing to save — because another
 * replica beat it to the row — must not roll back the check results already
 * recorded for every other workflow in the batch. {@link WorkflowScheduler}
 * therefore calls this method once per workflow, through the Spring proxy, so
 * each gets its own transaction.
 */
@Service
public class AdvanceWorkflowsUseCase {

    private static final Logger log = LoggerFactory.getLogger(AdvanceWorkflowsUseCase.class);

    private final WorkflowRepository workflows;
    private final OutboxWriter outbox;
    private final CheckExecutor checks;
    private final AssessmentPolicy policy;
    private final Clock clock;

    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;

    public AdvanceWorkflowsUseCase(
            WorkflowRepository workflows,
            OutboxWriter outbox,
            CheckExecutor checks,
            AssessmentPolicy policy,
            Clock clock,
            @Value("${los.workflow.max-attempts:5}") int maxAttempts,
            @Value("${los.workflow.base-backoff:PT2S}") Duration baseBackoff,
            @Value("${los.workflow.max-backoff:PT5M}") Duration maxBackoff) {
        this.workflows = workflows;
        this.outbox = outbox;
        this.checks = checks;
        this.policy = policy;
        this.clock = clock;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
    }

    /**
     * Runs every due check on one workflow and concludes it if every check has
     * now answered.
     *
     * <p>Public and called from another bean so the transaction proxy applies. A
     * self-invocation from a method on this class would silently run without a
     * transaction, and the outbox write would then commit separately from the
     * state change it describes.
     */
    @Transactional
    public void advanceOne(WorkflowId workflowId) {
        WorkflowInstance instance = workflows
                .findById(workflowId)
                .orElseThrow(() -> new IllegalStateException("Workflow disappeared while being advanced"));

        if (instance.status().isTerminal()) {
            return;
        }

        Instant now = clock.instant();
        for (CheckState due : instance.checksDueAt(now)) {
            runCheck(instance, due);
            if (instance.status().isTerminal()) {
                // A check was abandoned and the assessment failed. Nothing else
                // in this workflow is worth running.
                break;
            }
        }

        instance.concludeIfReady(policy, clock.instant());

        workflows.save(instance);
        outbox.append(instance.drainPendingEvents(), correlationIdFor(instance));
    }

    private void runCheck(WorkflowInstance instance, CheckState check) {
        int attempt = check.attempts() + 1;
        ExternalCheckPort.CheckRequest request = new ExternalCheckPort.CheckRequest(
                instance.applicationId(), instance.applicantReference(), instance.inputs(), attempt);

        try {
            ExternalCheckPort.CheckAnswer answer = checks.invoke(check.type(), request);
            instance.recordCheckOutcome(
                    check.type(), answer.outcome(), answer.reasonCode(), answer.score(), clock.instant());

            log.info(
                    "Check completed. applicationId={} check={} outcome={} attempt={}",
                    instance.applicationId(),
                    check.type(),
                    answer.outcome(),
                    attempt);

        } catch (PermanentCheckFailure e) {
            // No retry: the provider will answer the same way every time.
            instance.abandonCheck(check.type(), e.errorCode(), clock.instant());
            log.error(
                    "Check failed permanently and the assessment was abandoned. "
                            + "applicationId={} check={} errorCode={}",
                    instance.applicationId(),
                    check.type(),
                    e.errorCode());

        } catch (TransientCheckFailure e) {
            handleTransientFailure(instance, check, attempt, e.errorCode());
        }
    }

    private void handleTransientFailure(WorkflowInstance instance, CheckState check, int attempt, String errorCode) {
        if (attempt >= maxAttempts) {
            instance.abandonCheck(check.type(), WorkflowReasonCodes.CHECK_RETRY_BUDGET_EXHAUSTED, clock.instant());
            log.error(
                    "Check exhausted its retry budget; assessment abandoned for the replay runbook. "
                            + "applicationId={} check={} attempts={} errorCode={}",
                    instance.applicationId(),
                    check.type(),
                    attempt,
                    errorCode);
            return;
        }

        Duration backoff = backoffFor(attempt);
        instance.recordTransientFailure(check.type(), errorCode, backoff, clock.instant());
        log.warn(
                "Check attempt failed; scheduled for retry. applicationId={} check={} attempt={} "
                        + "retryInSeconds={} errorCode={}",
                instance.applicationId(),
                check.type(),
                attempt,
                backoff.toSeconds(),
                errorCode);
    }

    /**
     * Exponential backoff with full jitter.
     *
     * <p>Jitter is not cosmetic. Without it every assessment that failed during
     * the same provider outage retries at the same instant, and the provider is
     * hit by a synchronised burst exactly as it recovers — turning a short outage
     * into a long one.
     */
    private Duration backoffFor(int attempt) {
        long exponentialMillis = baseBackoff.toMillis() * (1L << Math.min(attempt - 1, 20));
        long cappedMillis = Math.min(exponentialMillis, maxBackoff.toMillis());
        long jitteredMillis = ThreadLocalRandom.current().nextLong(baseBackoff.toMillis(), cappedMillis + 1);
        return Duration.ofMillis(jitteredMillis);
    }

    /**
     * The correlation identifier carried onto events this workflow publishes.
     *
     * <p>Derived from the application identifier, so work the scheduler resumes
     * minutes after the customer's request finished is still traceable to that
     * application.
     */
    private static String correlationIdFor(WorkflowInstance instance) {
        return "wf-" + instance.applicationId();
    }
}
