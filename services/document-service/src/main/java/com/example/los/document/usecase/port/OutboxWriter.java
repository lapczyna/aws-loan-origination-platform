package com.example.los.document.usecase.port;

import java.util.List;

import com.example.los.document.domain.event.DocumentDomainEvent;

/**
 * Outbound port for the document context's transactional outbox.
 *
 * <p>Implementations must write inside the caller's transaction, so a scan result
 * cannot be recorded without being announced.
 */
public interface OutboxWriter {

    void append(List<DocumentDomainEvent> events, String correlationId);
}
