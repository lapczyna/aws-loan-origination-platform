package com.example.los.events.workflow;

/**
 * Assessment finished and produced a recommendation for the application service.
 *
 * @param applicationId  opaque application identifier
 * @param workflowId     opaque workflow instance identifier
 * @param recommendation APPROVE, REJECT or MANUAL_REVIEW
 * @param reasonCode     stable, safe reason code explaining the recommendation
 */
public record WorkflowCompletedV1(
        String applicationId, String workflowId, String recommendation, String reasonCode)
        implements WorkflowEventPayload {

    public static final int SCHEMA_VERSION = 1;
}
