package com.example.los.application.adapter.in.messaging;

import java.time.Clock;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.application.adapter.out.persistence.DocumentStatusProjector;
import com.example.los.application.usecase.port.ProcessedEventStore;
import com.example.los.events.EventJson;
import com.example.los.events.EventTypes;
import com.example.los.events.document.DocumentScanCompletedV1;
import com.example.los.events.document.DocumentUploadRequestedV1;

/**
 * Keeps the local document-status projection up to date.
 *
 * <p>Everything about this listener is shaped by at-least-once delivery.
 *
 * <ul>
 *   <li><b>Duplicates.</b> The event identifier is claimed in the de-duplication
 *       ledger inside the same transaction as the projection update, so a
 *       redelivered event cannot apply twice.
 *   <li><b>Out-of-order events.</b> The projection refuses to move backwards: a
 *       row is only advanced by an event carrying a higher source version. This
 *       is a second, independent guard, because a duplicate and a stale event are
 *       different failures — de-duplication does not help when the broker
 *       genuinely delivers an older event after a newer one, which happens after
 *       a partition reassignment.
 *   <li><b>Unknown event types.</b> Ignored and acknowledged. A newer document
 *       service publishing a type this version does not understand must not stall
 *       the partition for every other event behind it.
 * </ul>
 *
 * <p>The whole handler runs in one transaction. If it throws, nothing is
 * committed and the offset is not advanced, so the event is redelivered — which
 * is exactly what the de-duplication ledger is there to make safe.
 */
@Component
class DocumentEventListener {

    private static final Logger log = LoggerFactory.getLogger(DocumentEventListener.class);
    private static final String CONSUMER = "application-service.document-events";

    private final DocumentStatusProjector projector;
    private final ProcessedEventStore processedEvents;
    private final Clock clock;

    DocumentEventListener(
            DocumentStatusProjector projector, ProcessedEventStore processedEvents, Clock clock) {
        this.projector = projector;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    @KafkaListener(
            topics = "#{T(com.example.los.events.Topics).DOCUMENT_EVENTS}",
            groupId = "${los.kafka.consumer-group.document-events:application-service.document-events}")
    @Transactional
    public void onDocumentEvent(String message) {
        EventJson.EventHeader header = EventJson.readHeader(message);

        if (!processedEvents.markProcessed(header.eventId(), CONSUMER)) {
            log.debug("Duplicate document event ignored. eventId={}", header.eventId());
            return;
        }

        switch (header.eventType()) {
            case EventTypes.DOCUMENT_UPLOAD_REQUESTED -> {
                DocumentUploadRequestedV1 payload =
                        EventJson.readEnvelope(message, DocumentUploadRequestedV1.class).payload();
                projector.recordUploadRequested(
                        UUID.fromString(payload.documentId()),
                        UUID.fromString(payload.applicationId()),
                        payload.documentType(),
                        header.aggregateVersion(),
                        clock.instant());
            }
            case EventTypes.DOCUMENT_SCAN_COMPLETED -> {
                DocumentScanCompletedV1 payload =
                        EventJson.readEnvelope(message, DocumentScanCompletedV1.class).payload();
                projector.recordScanOutcome(
                        UUID.fromString(payload.documentId()),
                        UUID.fromString(payload.applicationId()),
                        payload.documentType(),
                        payload.outcome(),
                        header.aggregateVersion(),
                        clock.instant());
            }
            default -> log.debug(
                    "Ignoring document event type this service does not handle. eventType={} eventId={}",
                    header.eventType(),
                    header.eventId());
        }
    }
}
