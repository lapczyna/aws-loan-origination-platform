package com.example.los.application.domain.event;

import java.time.Instant;

import com.example.los.application.domain.model.ApplicationId;

/** The content of a draft was changed. */
public record DraftUpdated(ApplicationId applicationId, long aggregateVersion, Instant occurredAt)
        implements ApplicationDomainEvent {}
