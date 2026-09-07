package com.example.los.workflow.usecase.port;

/**
 * Outbound port for consumer de-duplication.
 *
 * <p>The claim must happen in the same transaction as the business effect, so a
 * redelivered event cannot start a second assessment of the same application.
 */
public interface ProcessedEventStore {

    /** @return {@code true} on the first delivery, {@code false} for a duplicate */
    boolean markProcessed(String eventId, String consumer);
}
