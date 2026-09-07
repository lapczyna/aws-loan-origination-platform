package com.example.los.document.adapter.out.persistence;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.document.domain.event.DocumentDomainEvent;
import com.example.los.document.domain.event.ScanCompleted;
import com.example.los.document.domain.event.UploadCompleted;
import com.example.los.document.domain.event.UploadRequested;
import com.example.los.document.usecase.port.OutboxWriter;
import com.example.los.events.AggregateTypes;
import com.example.los.events.EventEnvelope;
import com.example.los.events.EventJson;
import com.example.los.events.EventMetadataKeys;
import com.example.los.events.EventTypes;
import com.example.los.events.Topics;
import com.example.los.events.document.DocumentEventPayload;
import com.example.los.events.document.DocumentScanCompletedV1;
import com.example.los.events.document.DocumentUploadRequestedV1;

/**
 * Translates document domain events into published contracts.
 *
 * <p>{@link UploadCompleted} is deliberately not published. "The bytes arrived"
 * is internal bookkeeping: nothing outside this context may act on a document
 * that has not been scanned, and publishing the event would invite a consumer to
 * try. The facts other contexts need are that an upload was requested and how the
 * scan turned out.
 *
 * <p>The presigned URL never appears in any published event. It is a bearer
 * credential, and an event is persisted, replicated across brokers, consumed by
 * several services and copied into an audit store.
 */
@Component
class DocumentOutboxWriter implements OutboxWriter {

    private static final String PRODUCER = "document-service";

    private final DocumentOutboxJpaRepository outbox;
    private final Clock clock;
    private final String environment;

    DocumentOutboxWriter(
            DocumentOutboxJpaRepository outbox,
            Clock clock,
            @Value("${los.environment:local}") String environment) {
        this.outbox = outbox;
        this.clock = clock;
        this.environment = environment;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void append(List<DocumentDomainEvent> events, String correlationId) {
        String causationId = null;

        for (DocumentDomainEvent event : events) {
            Published published = translate(event);
            if (published == null) {
                continue;
            }

            String eventId = UUID.randomUUID().toString();
            EventEnvelope<DocumentEventPayload> envelope = new EventEnvelope<>(
                    eventId,
                    published.eventType(),
                    published.schemaVersion(),
                    AggregateTypes.DOCUMENT,
                    event.documentId().toString(),
                    event.aggregateVersion(),
                    event.occurredAt(),
                    correlationId,
                    causationId,
                    Map.of(EventMetadataKeys.PRODUCER, PRODUCER, EventMetadataKeys.ENVIRONMENT, environment),
                    published.payload());

            outbox.save(new DocumentOutboxEventEntity(
                    UUID.fromString(eventId),
                    AggregateTypes.DOCUMENT,
                    event.documentId().toString(),
                    event.aggregateVersion(),
                    published.eventType(),
                    published.schemaVersion(),
                    Topics.DOCUMENT_EVENTS,
                    // The APPLICATION identifier, not the document's. Consumers
                    // maintain per-application projections and need every document
                    // event for one application to arrive on one partition in order.
                    event.applicationId(),
                    EventJson.write(envelope),
                    correlationId,
                    causationId,
                    clock.instant()));

            causationId = eventId;
        }
    }

    private record Published(String eventType, int schemaVersion, DocumentEventPayload payload) {}

    /**
     * Exhaustive translation from domain event to published contract.
     *
     * @return the contract to publish, or {@code null} for an internal event
     */
    private Published translate(DocumentDomainEvent event) {
        return switch (event) {
            case UploadRequested requested -> new Published(
                    EventTypes.DOCUMENT_UPLOAD_REQUESTED,
                    DocumentUploadRequestedV1.SCHEMA_VERSION,
                    new DocumentUploadRequestedV1(
                            requested.documentId().toString(),
                            requested.applicationId(),
                            requested.documentType().name(),
                            requested.objectKey()));

            case ScanCompleted completed -> new Published(
                    EventTypes.DOCUMENT_SCAN_COMPLETED,
                    DocumentScanCompletedV1.SCHEMA_VERSION,
                    new DocumentScanCompletedV1(
                            completed.documentId().toString(),
                            completed.applicationId(),
                            completed.documentType().name(),
                            completed.outcome().name(),
                            completed.reasonCode(),
                            completed.sizeBytes()));

            // Internal: nothing outside this context may act on a document that
            // has not been scanned, so announcing its arrival invites misuse.
            case UploadCompleted _ -> null;
        };
    }
}
