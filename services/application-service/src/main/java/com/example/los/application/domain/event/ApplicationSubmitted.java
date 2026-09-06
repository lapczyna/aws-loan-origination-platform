package com.example.los.application.domain.event;

import java.time.Instant;

import com.example.los.application.domain.model.ApplicantReference;
import com.example.los.application.domain.model.ApplicationId;
import com.example.los.application.domain.model.LoanRequest;

/** The application was submitted for assessment. Triggers the workflow. */
public record ApplicationSubmitted(
        ApplicationId applicationId,
        ApplicantReference applicantReference,
        LoanRequest request,
        long aggregateVersion,
        Instant occurredAt)
        implements ApplicationDomainEvent {}
