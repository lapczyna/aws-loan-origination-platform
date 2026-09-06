package com.example.los.events.workflow;

/**
 * A single external check finished.
 *
 * <p>All checks are SIMULATED. No real KYC, AML, fraud or credit bureau system
 * is contacted anywhere in this repository.
 *
 * @param applicationId opaque application identifier
 * @param workflowId    opaque workflow instance identifier
 * @param checkType     KYC, AML, FRAUD or CREDIT_SCORE
 * @param outcome       PASSED, FAILED or INCONCLUSIVE
 * @param reasonCode    stable, safe reason code
 * @param score         normalised risk score between 0 and 1000, or null when not applicable
 * @param attempt       one-based attempt number that produced this outcome
 */
public record WorkflowCheckCompletedV1(
        String applicationId,
        String workflowId,
        String checkType,
        String outcome,
        String reasonCode,
        Integer score,
        int attempt)
        implements WorkflowEventPayload {

    public static final int SCHEMA_VERSION = 1;
}
