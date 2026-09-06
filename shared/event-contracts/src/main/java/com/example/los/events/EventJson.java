package com.example.los.events;

import java.time.Instant;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single canonical JSON mapping for events on this platform.
 *
 * <p>Every producer and every consumer uses this configuration. Defining it once
 * is what makes the compatibility suite meaningful: a test that round-trips a
 * golden sample through a differently configured mapper proves nothing about
 * what the services actually put on the wire.
 *
 * <p>Two settings carry the compatibility guarantees:
 *
 * <ul>
 *   <li><b>Unknown properties are ignored.</b> A consumer built against schema
 *       version N keeps working when a producer starts emitting version N+1 with
 *       additional optional fields. This is what makes additive change safe.
 *   <li><b>Timestamps are ISO-8601 strings in UTC.</b> Numeric epoch timestamps
 *       are unreadable in a dead-letter queue and lose precision guarantees
 *       across languages.
 * </ul>
 *
 * <p>Removing a field, renaming a field, narrowing a type or changing the meaning
 * of a value is <em>not</em> additive. Those changes require a new schema version
 * and a new topic. {@code EventContractCompatibilityTest} enforces this by
 * replaying the golden samples of every released version.
 */
public final class EventJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private EventJson() {}

    /**
     * The shared, thread-safe mapper. Jackson mappers are immutable once built,
     * so handing out the instance is safe; callers must not attempt to
     * reconfigure it.
     */
    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }

    /** Reads an envelope whose payload is of the given type. */
    public static <T> EventEnvelope<T> readEnvelope(String json, Class<T> payloadType) {
        return MAPPER.readValue(
                json, MAPPER.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType));
    }

    /** Reads only the routing fields, without binding the payload. Used by generic consumers. */
    public static EventHeader readHeader(String json) {
        return MAPPER.readValue(json, EventHeader.class);
    }

    /**
     * The subset of the envelope a generic consumer needs in order to route,
     * de-duplicate and order an event without understanding its payload.
     */
    public record EventHeader(
            String eventId,
            String eventType,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            Instant occurredAt,
            String correlationId,
            String causationId) {}
}
