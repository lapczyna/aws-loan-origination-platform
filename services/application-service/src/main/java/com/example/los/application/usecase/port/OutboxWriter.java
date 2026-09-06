package com.example.los.application.usecase.port;

import java.util.List;

import com.example.los.application.domain.event.ApplicationDomainEvent;

/**
 * Outbound port for the transactional outbox.
 *
 * <p>Implementations MUST write inside the caller's transaction. That is the
 * entire point: if the events were written outside it, a rollback would leave
 * events describing a state change that never happened, and a crash between
 * commit and publish would lose events describing one that did.
 */
public interface OutboxWriter {

    /**
     * Translates domain events into published contracts and appends them to the
     * outbox within the current transaction.
     *
     * @param events        the events drained from the aggregate
     * @param correlationId identifier of the business interaction that caused them
     */
    void append(List<ApplicationDomainEvent> events, String correlationId);
}
