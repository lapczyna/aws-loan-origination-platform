package com.example.los.application.adapter.in.web.dto;

import java.time.Instant;

import com.example.los.application.domain.model.LoanApplication;

/**
 * The API representation of an application.
 *
 * <p>Carries no personal data. The applicant is identified by the pseudonymous
 * reference and, for a human reviewer's benefit, by initials only. That is a
 * deliberate design choice rather than an oversight: a caller holding a token
 * for this API can enumerate applications, and an API that returns a name, an
 * email address and a date of birth turns any leaked token into a data breach.
 *
 * <p>The same property is what makes this representation safe to store verbatim
 * in the idempotency ledger and to replay to a retrying client.
 */
public record ApplicationResponse(
        String applicationId,
        String applicantReference,
        String applicantInitials,
        String residenceCountry,
        String status,
        long amountMinorUnits,
        String currency,
        int termMonths,
        String purpose,
        String productCode,
        DecisionResponse decision,
        long version,
        Instant createdAt,
        Instant submittedAt,
        Instant updatedAt) {

    /** The decision, when one has been made. */
    public record DecisionResponse(String type, String reasonCode, String decidedBy) {}

    public static ApplicationResponse from(LoanApplication application) {
        return new ApplicationResponse(
                application.id().toString(),
                application.applicantReference().value(),
                application.applicant().displayInitials(),
                application.applicant().residenceCountry(),
                application.status().name(),
                application.request().amount().minorUnits(),
                application.request().amount().currency().getCurrencyCode(),
                application.request().term().months(),
                application.request().purpose().name(),
                application.request().productCode(),
                application.decision() == null
                        ? null
                        : new DecisionResponse(
                                application.decision().type().name(),
                                application.decision().reasonCode(),
                                application.decision().decidedBy()),
                application.version(),
                application.createdAt(),
                application.submittedAt(),
                application.updatedAt());
    }
}
