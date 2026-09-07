package com.example.los.workflow.adapter.out.persistence;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.events.AggregateTypes;
import com.example.los.events.EventEnvelope;
import com.example.los.events.EventJson;
import com.example.los.events.EventMetadataKeys;
import com.example.los.events.EventTypes;
import com.example.los.events.Topics;
import com.example.los.events.workflow.WorkflowCheckCompletedV1;
import com.example.los.events.workflow.WorkflowCompletedV1;
import com.example.los.events.workflow.WorkflowEventPayload;
import com.example.los.events.workflow.WorkflowFailedV1;
import com.example.los.events.workflow.WorkflowStartedV1;
import com.example.los.workflow.domain.event.CheckCompleted;
import com.example.los.workflow.domain.event.WorkflowCompleted;
import com.example.los.workflow.domain.event.WorkflowDomainEvent;
import com.example.los.workflow.domain.event.WorkflowFailed;
import com.example.los.workflow.domain.event.WorkflowStarted;
import com.example.los.workflow.usecase.port.OutboxWriter;

/**
 * Translates workflow domain events into published contracts and appends them to
 * this context's outbox.
 *
 * <p>{@link Propagation#MANDATORY} is load-bearing: appending must happen inside
 * the transaction that recorded the decision. Given its own transaction, a crash
 * between the two commits would leave an assessment that decided something and
 * never told anyone, or an announcement of a decision that rolled back.
 *
 * <p>Unlike the application context, every domain event here is published. All
 * four are facts other contexts act on or audit: the application service reacts
 * to completion and failure, and the audit trail needs the per-check history to
 * explain how a decision was reached.
 */
@Component
class WorkflowOutboxWriter implements OutboxWriter {

    private static final String PRODUCER = "workflow-service";

    private final WorkflowOutboxJpaRepository outbox;
    private final Clock clock;
    private final String environment;

    WorkflowOutboxWriter(
            WorkflowOutboxJpaRepository outbox,
            Clock clock,
            @Value("${los.environment:local}") String environment) {
        this.outbox = outbox;
        this.clock = clock;
        this.environment = environment;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(List<WorkflowDomainEvent> events, String correlationId) {
        String causationId = null;

        for (WorkflowDomainEvent event : events) {
            Published published = translate(event);
            String eventId = UUID.randomUUID().toString();

            EventEnvelope<WorkflowEventPayload> envelope = new EventEnvelope<>(
                    eventId,
                    published.eventType(),
                    published.schemaVersion(),
                    AggregateTypes.WORKFLOW,
                    event.workflowId().toString(),
                    event.aggregateVersion(),
                    event.occurredAt(),
                    correlationId,
                    causationId,
                    Map.of(EventMetadataKeys.PRODUCER, PRODUCER, EventMetadataKeys.ENVIRONMENT, environment),
                    published.payload());

            outbox.save(new WorkflowOutboxEventEntity(
                    UUID.fromString(eventId),
                    AggregateTypes.WORKFLOW,
                    event.workflowId().toString(),
                    event.aggregateVersion(),
                    published.eventType(),
                    published.schemaVersion(),
                    Topics.WORKFLOW_EVENTS,
                    // The APPLICATION identifier, not the workflow's. Consumers
                    // care about ordering per application, and they correlate
                    // workflow events with application events on that key.
                    event.applicationId(),
                    EventJson.write(envelope),
                    correlationId,
                    causationId,
                    clock.instant()));

            causationId = eventId;
        }
    }

    private record Published(String eventType, int schemaVersion, WorkflowEventPayload payload) {}

    /** Exhaustive translation from domain event to published contract. */
    private Published translate(WorkflowDomainEvent event) {
        return switch (event) {
            case WorkflowStarted started -> new Published(
                    EventTypes.WORKFLOW_STARTED,
                    WorkflowStartedV1.SCHEMA_VERSION,
                    new WorkflowStartedV1(
                            started.applicationId(),
                            started.workflowId().toString(),
                            started.checks().stream().map(Enum::name).toList()));

            case CheckCompleted completed -> new Published(
                    EventTypes.WORKFLOW_CHECK_COMPLETED,
                    WorkflowCheckCompletedV1.SCHEMA_VERSION,
                    new WorkflowCheckCompletedV1(
                            completed.applicationId(),
                            completed.workflowId().toString(),
                            completed.checkType().name(),
                            completed.outcome().name(),
                            completed.reasonCode(),
                            completed.score(),
                            completed.attempt()));

            case WorkflowCompleted completed -> new Published(
                    EventTypes.WORKFLOW_COMPLETED,
                    WorkflowCompletedV1.SCHEMA_VERSION,
                    new WorkflowCompletedV1(
                            completed.applicationId(),
                            completed.workflowId().toString(),
                            completed.recommendation().name(),
                            completed.reasonCode()));

            case WorkflowFailed failed -> new Published(
                    EventTypes.WORKFLOW_FAILED,
                    WorkflowFailedV1.SCHEMA_VERSION,
                    new WorkflowFailedV1(
                            failed.applicationId(),
                            failed.workflowId().toString(),
                            failed.failedCheck().name(),
                            failed.errorCode(),
                            failed.attempts()));
        };
    }
}
