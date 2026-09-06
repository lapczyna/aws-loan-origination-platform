package com.example.los.application.adapter.in.web.dto;

import java.time.Instant;

import com.example.los.application.domain.model.LoanApplication;

/**
 * A lightweight status projection.
 *
 * <p>Exists as its own endpoint because status polling is the highest-volume
 * read on the platform, and a client polling for a state change should not have
 * to transfer, or be trusted with, the whole application on every poll.
 */
public record ApplicationStatusResponse(
        String applicationId,
        String status,
        boolean terminal,
        String decisionType,
        String reasonCode,
        long version,
        Instant updatedAt) {

    public static ApplicationStatusResponse from(LoanApplication application) {
        return new ApplicationStatusResponse(
                application.id().toString(),
                application.status().name(),
                application.status().isTerminal(),
                application.decision() == null ? null : application.decision().type().name(),
                application.decision() == null ? null : application.decision().reasonCode(),
                application.version(),
                application.updatedAt());
    }
}
