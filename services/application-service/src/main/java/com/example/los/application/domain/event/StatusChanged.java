package com.example.los.application.domain.event;

import java.time.Instant;

import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.ApplicationStatus;

/** The lifecycle status changed. */
public record StatusChanged(
        ApplicationId applicationId,
        ApplicationStatus previousStatus,
        ApplicationStatus newStatus,
        String reasonCode,
        long aggregateVersion,
        Instant occurredAt)
        implements ApplicationDomainEvent {}
