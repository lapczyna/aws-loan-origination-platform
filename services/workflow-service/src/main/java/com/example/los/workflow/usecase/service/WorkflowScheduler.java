package com.example.los.workflow.usecase.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.los.workflow.domain.model.WorkflowId;
import com.example.los.workflow.usecase.port.ConcurrentWorkflowUpdate;
import com.example.los.workflow.usecase.port.WorkflowRepository;

/**
 * Polls for assessments with work due and hands each to
 * {@link AdvanceWorkflowsUseCase}.
 *
 * <p>Split from the use case for a reason that is easy to get wrong: Spring's
 * {@code @Transactional} is applied by a proxy, so a method calling another
 * method on the same object bypasses it entirely. If the loop and
 * {@code advanceOne} lived on one class, every advance would silently run
 * without a transaction, and the outbox write would commit separately from the
 * state change it describes — quietly destroying the guarantee the outbox
 * exists to provide. Calling across beans keeps the proxy in the path.
 *
 * <p>The poll is deliberately a poll. The work is time-based: a check that failed
 * transiently becomes due again at an instant in the future, and nothing will
 * publish an event to announce it.
 */
@Component
public class WorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(WorkflowScheduler.class);

    private final WorkflowRepository workflows;
    private final AdvanceWorkflowsUseCase advancer;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;

    public WorkflowScheduler(
            WorkflowRepository workflows,
            AdvanceWorkflowsUseCase advancer,
            Clock clock,
            @Value("${los.workflow.batch-size:50}") int batchSize,
            @Value("${los.workflow.lease:PT1M}") Duration lease) {
        this.workflows = workflows;
        this.advancer = advancer;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lease = lease;
    }

    /**
     * Leases a batch of due workflows and advances each one.
     *
     * <p>The lease must comfortably exceed the time it takes to run one
     * workflow's checks. Too short and a second replica picks up a workflow this
     * one is still working on; too long and a crashed replica's workflows sit
     * idle until it expires. It is configuration rather than a constant because
     * the right value depends on how slow the real providers are.
     *
     * @return how many workflows were advanced
     */
    @Scheduled(fixedDelayString = "${los.workflow.poll-interval:PT1S}")
    public int advanceDueWorkflows() {
        List<WorkflowId> leased = workflows.leaseDueWorkflows(clock.instant(), lease, batchSize);
        if (leased.isEmpty()) {
            return 0;
        }

        int advanced = 0;
        for (WorkflowId id : leased) {
            try {
                advancer.advanceOne(id);
                advanced++;
            } catch (ConcurrentWorkflowUpdate e) {
                // Another replica got there first. Expected under concurrency,
                // not an incident: the winner has already recorded the outcome.
                log.debug("Workflow advanced concurrently by another replica. workflowId={}", id);
            } catch (RuntimeException e) {
                // Deliberately broad and deliberately per-workflow. One
                // workflow's failure must not abandon the rest of the batch, and
                // the lease will expire so this one is retried rather than lost.
                log.error(
                        "Advancing a workflow failed; it will be retried when its lease expires. "
                                + "workflowId={} type={}",
                        id,
                        e.getClass().getSimpleName(),
                        e);
            }
        }

        if (advanced > 0) {
            log.debug("Advanced {} of {} leased workflows", advanced, leased.size());
        }
        return advanced;
    }
}
