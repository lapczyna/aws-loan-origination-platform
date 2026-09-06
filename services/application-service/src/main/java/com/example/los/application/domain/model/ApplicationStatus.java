package com.example.los.application.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * The lifecycle of a loan application.
 *
 * <p>The legal transitions are declared here, in one place, rather than being
 * implied by scattered {@code if} statements across services. Every transition
 * the platform permits is visible in {@link #allowedTransitions()}, and
 * {@link LoanApplication} is the only type allowed to perform one.
 *
 * <pre>
 *   DRAFT ──────────► SUBMITTED ──► VALIDATING ──► CHECKS_IN_PROGRESS
 *     │                                  │                │
 *     │                                  ▼                ├──► APPROVED
 *     ▼                              REJECTED             ├──► REJECTED
 *  CANCELLED                                              ├──► MANUAL_REVIEW ──► APPROVED
 *                                                         │                  └─► REJECTED
 *                                                         └──► FAILED
 * </pre>
 */
public enum ApplicationStatus {

    /** Being filled in. The only status in which the application content may change. */
    DRAFT,

    /** Handed over for assessment. Content is frozen from this point on. */
    SUBMITTED,

    /** Undergoing synchronous validation of completeness and product eligibility. */
    VALIDATING,

    /** External KYC, AML, fraud and credit checks are running. */
    CHECKS_IN_PROGRESS,

    /** Awaiting a decision from a human reviewer. */
    MANUAL_REVIEW,

    /** Terminal: the loan was granted. */
    APPROVED,

    /** Terminal: the loan was declined on business grounds. */
    REJECTED,

    /**
     * Terminal: assessment could not be completed for technical reasons. Distinct
     * from {@link #REJECTED} because it says nothing about the applicant and is
     * the state the replay runbook operates on.
     */
    FAILED,

    /** Terminal: withdrawn before submission. */
    CANCELLED;

    private static final Set<ApplicationStatus> TERMINAL = EnumSet.of(APPROVED, REJECTED, FAILED, CANCELLED);

    /**
     * The statuses this status may legally move to.
     *
     * <p>Implemented with a switch over {@code this} so the compiler fails the
     * build when a new status is added and this method is not updated.
     */
    public Set<ApplicationStatus> allowedTransitions() {
        return switch (this) {
            case DRAFT -> EnumSet.of(SUBMITTED, CANCELLED);
            case SUBMITTED -> EnumSet.of(VALIDATING, FAILED);
            // Validation can reject outright: an ineligible product or a missing
            // mandatory document never reaches the external checks.
            case VALIDATING -> EnumSet.of(CHECKS_IN_PROGRESS, REJECTED, FAILED);
            case CHECKS_IN_PROGRESS -> EnumSet.of(APPROVED, REJECTED, MANUAL_REVIEW, FAILED);
            case MANUAL_REVIEW -> EnumSet.of(APPROVED, REJECTED, FAILED);
            case APPROVED, REJECTED, FAILED, CANCELLED -> EnumSet.noneOf(ApplicationStatus.class);
        };
    }

    public boolean canTransitionTo(ApplicationStatus target) {
        return allowedTransitions().contains(target);
    }

    /** True when no further transition is possible. */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** True while the platform is actively working on the application. */
    public boolean isInProgress() {
        return this == SUBMITTED || this == VALIDATING || this == CHECKS_IN_PROGRESS;
    }

    /** True when the application content may still be edited. */
    public boolean isEditable() {
        return this == DRAFT;
    }
}
