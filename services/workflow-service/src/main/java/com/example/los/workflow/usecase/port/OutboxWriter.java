package com.example.los.workflow.usecase.port;

import java.util.List;

import com.example.los.workflow.domain.event.WorkflowDomainEvent;

/**
 * Outbound port for the workflow context's transactional outbox.
 *
 * <p>Implementations must write inside the caller's transaction, so a workflow
 * that recorded a decision cannot fail to announce it, and an announcement can
 * never describe a decision that rolled back.
 */
public interface OutboxWriter {

    void append(List<WorkflowDomainEvent> events, String correlationId);
}
