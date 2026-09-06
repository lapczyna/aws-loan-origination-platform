package com.example.los.events.application;

/**
 * Marker for every payload published on the application events topic.
 *
 * <p>Sealed so that the complete set of facts this bounded context publishes is
 * visible in one place and a consumer can exhaustively pattern match on it.
 */
public sealed interface ApplicationEventPayload
        permits ApplicationSubmittedV1, ApplicationStatusChangedV1, ApplicationDecisionRecordedV1 {

    /** Opaque identifier of the loan application this fact concerns. */
    String applicationId();
}
