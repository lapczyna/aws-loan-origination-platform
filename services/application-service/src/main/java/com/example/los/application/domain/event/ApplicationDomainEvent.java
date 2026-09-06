package com.example.los.application.domain.event;

import java.time.Instant;

import com.example.los.application.domain.model.ApplicationId;

/**
 * A fact recorded by the loan application aggregate.
 *
 * <p>These are the <em>internal</em> domain events. They are translated into the
 * published contracts in {@code com.example.los.events} by an outbound adapter,
 * which is what keeps the wire format free to evolve separately from the model.
 *
 * <p>Sealed so the translation is exhaustive: adding a domain event without
 * deciding whether and how it is published fails compilation.
 */
public sealed interface ApplicationDomainEvent
        permits ApplicationCreated,
                DraftUpdated,
                ApplicationSubmitted,
                StatusChanged,
                DecisionRecorded,
                ApplicationCancelled {

    ApplicationId applicationId();

    /** Version of the aggregate immediately after this event was applied. */
    long aggregateVersion();

    Instant occurredAt();
}
