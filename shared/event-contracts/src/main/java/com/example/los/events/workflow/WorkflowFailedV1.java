package com.example.los.events.workflow;

/**
 * Assessment could not be completed and the retry budget was exhausted.
 *
 * <p>This is a technical failure, not a business rejection. It routes the
 * application to the FAILED state and the workflow record to the dead-letter
 * runbook for controlled replay.
 *
 * @param applicationId opaque application identifier
 * @param workflowId    opaque workflow instance identifier
 * @param failedCheck   the check that exhausted its budget
 * @param errorCode     stable, safe error code; never an exception message
 * @param attempts      number of attempts made before giving up
 */
public record WorkflowFailedV1(
        String applicationId, String workflowId, String failedCheck, String errorCode, int attempts)
        implements WorkflowEventPayload {

    public static final int SCHEMA_VERSION = 1;
}
