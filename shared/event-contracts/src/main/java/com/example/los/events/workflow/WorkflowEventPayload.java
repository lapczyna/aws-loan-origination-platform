package com.example.los.events.workflow;

/** Marker for every payload published on the workflow events topic. */
public sealed interface WorkflowEventPayload
        permits WorkflowStartedV1, WorkflowCheckCompletedV1, WorkflowCompletedV1, WorkflowFailedV1 {

    /** Opaque identifier of the loan application being assessed; also the partition key. */
    String applicationId();
}
