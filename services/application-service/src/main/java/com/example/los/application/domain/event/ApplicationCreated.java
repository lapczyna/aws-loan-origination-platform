package com.example.los.application.domain.event;

import java.time.Instant;

import com.example.los.application.domain.model.ApplicantReference;
import com.example.los.application.domain.model.ApplicationId;

/** A draft application was created. */
public record ApplicationCreated(
        ApplicationId applicationId,
        ApplicantReference applicantReference,
        long aggregateVersion,
        Instant occurredAt)
        implements ApplicationDomainEvent {}
