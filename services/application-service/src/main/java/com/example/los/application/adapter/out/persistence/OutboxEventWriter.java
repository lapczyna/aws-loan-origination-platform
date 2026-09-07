package com.example.los.application.adapter.out.persistence;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.application.domain.event.ApplicationCancelled;
import com.example.los.application.domain.event.ApplicationCreated;
import com.example.los.application.domain.event.ApplicationDomainEvent;
import com.example.los.application.domain.event.ApplicationSubmitted;
import com.example.los.application.domain.event.DecisionRecorded;
import com.example.los.application.domain.event.DraftUpdated;
import com.example.los.application.domain.event.StatusChanged;
import com.example.los.application.usecase.port.OutboxWriter;
import com.example.los.events.AggregateTypes;
import com.example.los.events.EventEnvelope;
import com.example.los.events.EventJson;
import com.example.los.events.EventMetadataKeys;
import com.example.los.events.EventTypes;
import com.example.los.events.Topics;
import com.example.los.events.application.ApplicationDecisionRecordedV1;
import com.example.los.events.application.ApplicationEventPayload;
import com.example.los.events.application.ApplicationStatusChangedV1;
import com.example.los.events.application.ApplicationSubmittedV1;

/**
 * Translates internal domain events into published contracts and appends them to
 * the outbox.
 *
 * <p>This is the boundary at which the model stops and the wire format starts.
 * Not every domain event is published: {@code ApplicationCreated} and
 * {@code DraftUpdated} are internal bookkeeping about a draft that nothing
 * outside this context needs, and publishing them would leak the editing
 * behaviour of one context into the contract of every other. The translation is
 * an exhaustive switch over a sealed type, so adding a domain event forces an
 * explicit decision about whether it is published.
 *
 * <p>{@link Propagation#MANDATORY} is deliberate and load-bearing: this method
 * must never start its own transaction. If it did, events could commit
 * independently of the state change that produced them, which is precisely the
 * failure the outbox pattern exists to prevent. Calling it outside a transaction
 * is a programming error and fails immediately.
 */
@Component
class OutboxEventWriter implements OutboxWriter {

    private static final String PRODUCER = "application-service";

    private final OutboxEventJpaRepository outbox;
    private final Clock clock;
    private final String environment;

    OutboxEventWriter(
            OutboxEventJpaRepository outbox,
            Clock clock,
            @Value("${los.environment:local}") String environment) {
        this.outbox = outbox;
        this.clock = clock;
        this.environment = environment;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(List<ApplicationDomainEvent> events, String correlationId) {
        String causationId = null;
        for (ApplicationDomainEvent event : events) {
            PublishedEvent published = translate(event);
            if (published == null) {
                continue; // Internal event, not part of any published contract.
            }

            String eventId = UUID.randomUUID().toString();
            EventEnvelope<ApplicationEventPayload> envelope = new EventEnvelope<>(
                    eventId,
                    published.eventType(),
                    published.schemaVersion(),
                    AggregateTypes.LOAN_APPLICATION,
                    event.applicationId().toString(),
                    event.aggregateVersion(),
                    event.occurredAt(),
                    correlationId,
                    causationId,
                    Map.of(EventMetadataKeys.PRODUCER, PRODUCER, EventMetadataKeys.ENVIRONMENT, environment),
                    published.payload());

            outbox.save(new OutboxEventEntity(
                    UUID.fromString(eventId),
                    AggregateTypes.LOAN_APPLICATION,
                    event.applicationId().toString(),
                    event.aggregateVersion(),
                    published.eventType(),
                    published.schemaVersion(),
                    Topics.APPLICATION_EVENTS,
                    // The application identifier is always the partition key, so
                    // every event about one application is ordered on one partition.
                    event.applicationId().toString(),
                    EventJson.write(envelope),
                    correlationId,
                    causationId,
                    clock.instant()));

            // Each event in a batch is caused by the one before it, which gives
            // an operator a causal chain to follow when reconstructing what
            // happened during an incident.
            causationId = eventId;
        }
    }

    private record PublishedEvent(String eventType, int schemaVersion, ApplicationEventPayload payload) {}

    /**
     * Exhaustive translation from domain event to published contract.
     *
     * @return the contract to publish, or {@code null} for an internal event
     */
    private PublishedEvent translate(ApplicationDomainEvent event) {
        return switch (event) {
            case ApplicationSubmitted submitted -> new PublishedEvent(
                    EventTypes.APPLICATION_SUBMITTED,
                    ApplicationSubmittedV1.SCHEMA_VERSION,
                    new ApplicationSubmittedV1(
                            submitted.applicationId().toString(),
                            submitted.applicantReference().value(),
                            submitted.request().amount().minorUnits(),
                            submitted.request().amount().currency().getCurrencyCode(),
                            submitted.request().term().months(),
                            submitted.request().purpose().name(),
                            submitted.request().declaredAnnualIncome().minorUnits(),
                            submitted.request().productCode()));

            case StatusChanged changed -> new PublishedEvent(
                    EventTypes.APPLICATION_STATUS_CHANGED,
                    ApplicationStatusChangedV1.SCHEMA_VERSION,
                    new ApplicationStatusChangedV1(
                            changed.applicationId().toString(),
                            changed.previousStatus().name(),
                            changed.newStatus().name(),
                            changed.reasonCode()));

            case DecisionRecorded recorded -> new PublishedEvent(
                    EventTypes.APPLICATION_DECISION_RECORDED,
                    ApplicationDecisionRecordedV1.SCHEMA_VERSION,
                    new ApplicationDecisionRecordedV1(
                            recorded.applicationId().toString(),
                            recorded.decision().type().name(),
                            recorded.decision().reasonCode(),
                            recorded.decision().decidedBy()));

            // Draft lifecycle is internal to this context. Publishing every edit
            // of a draft would make consumers aware of behaviour they cannot act
            // on, and would put a high-volume, low-value event on a topic that
            // downstream services have to filter. Cancellation is not lost by
            // omitting it here: the DRAFT -> CANCELLED transition is published as
            // a StatusChanged event like every other transition.
            case ApplicationCreated _ -> null;
            case DraftUpdated _ -> null;
            case ApplicationCancelled _ -> null;
        };
    }
}
