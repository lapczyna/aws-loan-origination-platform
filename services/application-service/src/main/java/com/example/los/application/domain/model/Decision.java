package com.example.los.application.domain.model;

import java.util.Objects;

/**
 * A decision recorded against an application.
 *
 * @param type       what was decided
 * @param reasonCode stable, safe reason code; never free text entered by a user,
 *                   because that text would flow into events, audit records and
 *                   customer-facing responses
 * @param decidedBy  {@code SYSTEM} for automated decisions, otherwise a
 *                   pseudonymous reviewer reference
 */
public record Decision(DecisionType type, String reasonCode, String decidedBy) {

    public static final String SYSTEM = "SYSTEM";

    public Decision {
        Objects.requireNonNull(type, "type must not be null");
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode must not be blank");
        }
        if (decidedBy == null || decidedBy.isBlank()) {
            throw new IllegalArgumentException("decidedBy must not be blank");
        }
    }

    public static Decision automatic(DecisionType type, String reasonCode) {
        return new Decision(type, reasonCode, SYSTEM);
    }

    public boolean isAutomatic() {
        return SYSTEM.equals(decidedBy);
    }

    /** The lifecycle status this decision moves the application to. */
    public ApplicationStatus resultingStatus() {
        return switch (type) {
            case APPROVED -> ApplicationStatus.APPROVED;
            case REJECTED -> ApplicationStatus.REJECTED;
            case MANUAL_REVIEW -> ApplicationStatus.MANUAL_REVIEW;
        };
    }
}
