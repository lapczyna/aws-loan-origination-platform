package com.example.los.events.application;

/**
 * A final or interim decision was recorded against a loan application.
 *
 * @param applicationId opaque application identifier
 * @param decision      APPROVED, REJECTED or MANUAL_REVIEW
 * @param reasonCode    stable, safe reason code explaining the decision
 * @param decidedBy     SYSTEM for automated decisions, or a pseudonymous reviewer reference
 */
public record ApplicationDecisionRecordedV1(
        String applicationId, String decision, String reasonCode, String decidedBy)
        implements ApplicationEventPayload {

    public static final int SCHEMA_VERSION = 1;
}
