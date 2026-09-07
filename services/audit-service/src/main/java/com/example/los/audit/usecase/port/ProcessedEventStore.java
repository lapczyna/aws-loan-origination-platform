package com.example.los.audit.usecase.port;

/**
 * Outbound port for consumer de-duplication.
 *
 * <p>Belt and braces alongside the unique constraint on the audit table: this
 * short-circuits a duplicate before any hash is computed, and the constraint
 * catches anything that races past it.
 */
public interface ProcessedEventStore {

    /** @return {@code true} on the first delivery, {@code false} for a duplicate */
    boolean markProcessed(String eventId, String consumer);
}
