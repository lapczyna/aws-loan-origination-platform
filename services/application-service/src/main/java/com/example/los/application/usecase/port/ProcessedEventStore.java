package com.example.los.application.usecase.port;

/**
 * Outbound port for consumer de-duplication.
 *
 * <p>Kafka delivers at least once, so every consumer sees duplicates. The
 * business effect and the record of having seen the event are written in one
 * transaction; the store's uniqueness constraint is what turns a duplicate
 * delivery into a no-op instead of a second effect.
 */
public interface ProcessedEventStore {

    /**
     * Marks the event as processed by the given consumer.
     *
     * @return {@code true} if this is the first time, {@code false} if it is a duplicate
     */
    boolean markProcessed(String eventId, String consumer);
}
