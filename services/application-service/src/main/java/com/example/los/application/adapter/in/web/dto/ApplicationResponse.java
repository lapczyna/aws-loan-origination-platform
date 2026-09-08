package com.example.los.application.adapter.in.web.dto;

import java.time.Instant;

import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "Opaque, server-generated.", format = "uuid")
                String applicationId,
        @Schema(
                        description = """
                        An irreversible HMAC pseudonym, stable for the same applicant across \
                        applications.

                        **Pseudonymised, not anonymised**: the same applicant always produces \
                        the same value, so it is linkable and remains personal data under GDPR.
                        """,
                        pattern = "^APR-[0-9a-f]{16}$",
                        example = "APR-fa05675c49b42435")
                String applicantReference,
        @Schema(
                        description = "Initials only. The API never returns an applicant's name, "
                                + "not even to the client that supplied it.",
                        example = "T.A.")
                String applicantInitials,
        String residenceCountry,
        @Schema(description = "The application's position in its lifecycle.", allowableValues = {"DRAFT", "SUBMITTED", "VALIDATING", "CHECKS_IN_PROGRESS", "MANUAL_REVIEW", "APPROVED", "REJECTED", "FAILED", "CANCELLED"})
                String status,
        @Schema(
                        description = "The requested principal in the currency's MINOR units - cents, not "
                                + "euros. Money is never a floating-point number here.",
                        example = "1500000")
                long amountMinorUnits,
        String currency,
        int termMonths,
        String purpose,
        String productCode,
        @Schema(description = "Null until a decision has been recorded.")
                DecisionResponse decision,
        @Schema(description = "Optimistic-locking version.")
                long version,
        Instant createdAt,
        Instant submittedAt,
        Instant updatedAt) {

    /** The decision, when one has been made. */
    @Schema(name = "Decision", description = "The recorded outcome, once there is one.")
    public record DecisionResponse(
            @Schema(
                            description = """
                            `MANUAL_REVIEW` means the platform declined to decide. That is not the \
                            same as deciding against the applicant, and the record has to be able \
                            to tell the two apart later.
                            """,
                            allowableValues = {"APPROVED", "REJECTED", "MANUAL_REVIEW"})
                    String type,
            @Schema(
                            description = "A stable code, never prose. Free text about why a named "
                                    + "individual was refused credit is exactly what must not travel "
                                    + "through events into the audit store.",
                            example = "CREDIT_SCORE_BELOW_THRESHOLD")
                    String reasonCode,
            @Schema(
                            description = "The deciding reviewer's pseudonymous token subject, or null "
                                    + "when the decision was automatic. Never a name.")
                    String decidedBy) {}

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
