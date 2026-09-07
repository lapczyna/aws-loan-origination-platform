package com.example.los.workflow.usecase.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.workflow.domain.model.AssessmentInputs;
import com.example.los.workflow.domain.model.WorkflowId;
import com.example.los.workflow.domain.model.WorkflowInstance;
import com.example.los.workflow.usecase.port.OutboxWriter;
import com.example.los.workflow.usecase.port.ProcessedEventStore;
import com.example.los.workflow.usecase.port.WorkflowRepository;

/**
 * Begins an assessment when an application is submitted.
 *
 * <p>Guarded twice against starting the same assessment more than once, because
 * the two guards fail in different situations:
 *
 * <ul>
 *   <li>The <b>de-duplication ledger</b> catches a redelivery of the same event,
 *       which is normal traffic under at-least-once delivery.
 *   <li>The <b>lookup by application</b> catches a genuinely different event that
 *       nevertheless concerns an application already being assessed — a replayed
 *       topic, or a resubmission after an operator intervention.
 * </ul>
 *
 * <p>Neither is treated as an error. Starting a second assessment would call
 * every external provider a second time and could produce a contradictory
 * recommendation for one application, so both simply return.
 */
@Service
public class StartAssessmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(StartAssessmentUseCase.class);
    private static final String CONSUMER = "workflow-service.application-events";

    private final WorkflowRepository workflows;
    private final OutboxWriter outbox;
    private final ProcessedEventStore processedEvents;
    private final Clock clock;

    public StartAssessmentUseCase(
            WorkflowRepository workflows,
            OutboxWriter outbox,
            ProcessedEventStore processedEvents,
            Clock clock) {
        this.workflows = workflows;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    /** What happened, for metrics and for tests. */
    public enum Outcome {
        STARTED,
        DUPLICATE_EVENT_IGNORED,
        ALREADY_ASSESSING
    }

    @Transactional
    public Outcome start(
            String eventId,
            String applicationId,
            String applicantReference,
            AssessmentInputs inputs,
            String correlationId) {

        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("Duplicate submission event ignored. eventId={} applicationId={}", eventId, applicationId);
            return Outcome.DUPLICATE_EVENT_IGNORED;
        }

        if (workflows.findByApplicationId(applicationId).isPresent()) {
            log.info(
                    "Assessment already exists for this application; not starting another. "
                            + "applicationId={} correlationId={}",
                    applicationId,
                    correlationId);
            return Outcome.ALREADY_ASSESSING;
        }

        WorkflowInstance instance = WorkflowInstance.start(
                WorkflowId.newId(), applicationId, applicantReference, inputs, clock.instant());

        workflows.save(instance);
        outbox.append(instance.drainPendingEvents(), correlationId);

        // Identifiers and counts. The inputs are risk figures rather than
        // personal data, but they are still not logged: an amount plus a
        // pseudonym is more identifying together than either alone.
        log.info(
                "Assessment started. applicationId={} workflowId={} checks={} correlationId={}",
                applicationId,
                instance.id(),
                instance.checks().size(),
                correlationId);

        return Outcome.STARTED;
    }
}
