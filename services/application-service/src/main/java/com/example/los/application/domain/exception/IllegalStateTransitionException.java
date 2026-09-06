package com.example.los.application.domain.exception;

import com.example.los.application.domain.model.ApplicationStatus;

/** Raised when a caller attempts a lifecycle transition the state machine forbids. */
public class IllegalStateTransitionException extends DomainException {

    public static final String ERROR_CODE = "ILLEGAL_STATE_TRANSITION";

    private final ApplicationStatus from;
    private final ApplicationStatus to;

    public IllegalStateTransitionException(ApplicationStatus from, ApplicationStatus to) {
        super(ERROR_CODE, "Cannot transition application from " + from + " to " + to);
        this.from = from;
        this.to = to;
    }

    public ApplicationStatus from() {
        return from;
    }

    public ApplicationStatus to() {
        return to;
    }
}
