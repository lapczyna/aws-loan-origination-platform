package com.example.los.application.domain.event;

import java.time.Instant;

import com.example.los.application.domain.model.ApplicationId;

/** The draft was withdrawn before submission. */
public record ApplicationCancelled(
        ApplicationId applicationId, String reasonCode, long aggregateVersion, Instant occurredAt)
        implements ApplicationDomainEvent {}
