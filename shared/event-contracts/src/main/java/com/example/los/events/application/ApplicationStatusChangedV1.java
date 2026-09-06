package com.example.los.events.application;

/**
 * The lifecycle status of a loan application changed.
 *
 * @param applicationId  opaque application identifier
 * @param previousStatus status before the transition
 * @param newStatus      status after the transition
 * @param reasonCode     stable, safe reason code; never free text from a user
 */
public record ApplicationStatusChangedV1(
        String applicationId, String previousStatus, String newStatus, String reasonCode)
        implements ApplicationEventPayload {

    public static final int SCHEMA_VERSION = 1;
}
