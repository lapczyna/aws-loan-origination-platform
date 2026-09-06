package com.example.los.application.domain.event;

import java.time.Instant;

import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.Decision;

/** A decision was recorded, either automatically or by a human reviewer. */
public record DecisionRecorded(
        ApplicationId applicationId, Decision decision, long aggregateVersion, Instant occurredAt)
        implements ApplicationDomainEvent {}
