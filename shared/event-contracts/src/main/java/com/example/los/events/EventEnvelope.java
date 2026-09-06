package com.example.los.events;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Transport envelope shared by every event published on the platform.
 *
 * <p>The envelope is deliberately separate from the payload so that routing,
 * de-duplication, ordering and causality can be handled by infrastructure that
 * knows nothing about the business meaning of the event.
 *
 * <p><strong>Privacy contract:</strong> neither the envelope nor any payload may
 * carry personal data or document contents. {@code metadata} is restricted to
 * short, safe, operational values; it is never a place to smuggle applicant
 * details. See {@link EventMetadataKeys}.
 *
 * @param eventId          unique identifier of this event instance; the de-duplication key for consumers
 * @param eventType        stable logical name, for example {@code application.submitted}
 * @param schemaVersion    version of the payload schema, incremented on incompatible change
 * @param aggregateType    the kind of aggregate that produced the event
 * @param aggregateId      opaque identifier of the aggregate instance; also the Kafka partition key
 * @param aggregateVersion monotonically increasing version of the aggregate at the time of the event
 * @param occurredAt       UTC instant at which the business fact happened
 * @param correlationId    identifier shared by every event and request in one business interaction
 * @param causationId      the {@code eventId} of the event that directly caused this one, if any
 * @param metadata         small, safe operational key/value pairs
 * @param payload          the business fact
 * @param <T>              payload type
 */
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        long aggregateVersion,
        Instant occurredAt,
        String correlationId,
        String causationId,
        Map<String, String> metadata,
        T payload) {

    public EventEnvelope {
        eventId = requireText(eventId, "eventId");
        eventType = requireText(eventType, "eventType");
        aggregateType = requireText(aggregateType, "aggregateType");
        aggregateId = requireText(aggregateId, "aggregateId");
        correlationId = requireText(correlationId, "correlationId");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1");
        }
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion must be >= 0");
        }
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
