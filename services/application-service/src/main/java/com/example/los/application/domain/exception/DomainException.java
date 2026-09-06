package com.example.los.application.domain.exception;

/**
 * Base type for every failure the domain raises deliberately.
 *
 * <p>Each carries a stable {@code errorCode}. That code, not the message, is what
 * the API returns, what metrics are tagged with and what runbooks refer to.
 * Messages are for developers reading a log; they must never contain applicant
 * data or the value that failed validation.
 */
public abstract class DomainException extends RuntimeException {

    private final String errorCode;

    protected DomainException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
