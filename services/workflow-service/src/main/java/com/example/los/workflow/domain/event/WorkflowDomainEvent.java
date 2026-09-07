package com.example.los.workflow.domain.event;

import java.time.Instant;

import com.example.los.workflow.domain.model.WorkflowId;

/**
 * A fact recorded by a workflow instance.
 *
 * <p>Sealed so the translation into published contracts is exhaustive: adding a
 * domain event forces an explicit decision about whether it is published.
 */
public sealed interface WorkflowDomainEvent
        permits WorkflowStarted, CheckCompleted, WorkflowCompleted, WorkflowFailed {

    WorkflowId workflowId();

    /** The application being assessed. Also the Kafka partition key. */
    String applicationId();

    long aggregateVersion();

    Instant occurredAt();
}
