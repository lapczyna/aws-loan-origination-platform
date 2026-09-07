package com.example.los.workflow.domain.model;

import java.util.UUID;

/** Opaque identifier of a workflow instance. */
public record WorkflowId(UUID value) {

    public WorkflowId {
        if (value == null) {
            throw new IllegalArgumentException("WorkflowId value must not be null");
        }
    }

    public static WorkflowId newId() {
        return new WorkflowId(UUID.randomUUID());
    }

    public static WorkflowId of(String value) {
        return new WorkflowId(UUID.fromString(value));
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
