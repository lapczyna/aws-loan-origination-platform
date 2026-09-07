package com.example.los.workflow.domain.event;

import java.time.Instant;
import java.util.List;

import com.example.los.events.vocabulary.CheckType;
import com.example.los.workflow.domain.model.WorkflowId;

/** Assessment of an application began and the checks were scheduled. */
public record WorkflowStarted(
        WorkflowId workflowId,
        String applicationId,
        List<CheckType> checks,
        long aggregateVersion,
        Instant occurredAt)
        implements WorkflowDomainEvent {

    public WorkflowStarted {
        checks = List.copyOf(checks);
    }
}
