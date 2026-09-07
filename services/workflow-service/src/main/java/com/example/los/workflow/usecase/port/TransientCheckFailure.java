package com.example.los.workflow.usecase.port;

/**
 * The provider could not be reached, or did not answer in time.
 *
 * <p>Retryable. The applicant has not been assessed and nothing has been decided
 * about them.
 *
 * <p>The message carries a stable error code and never the provider's own error
 * text, which may quote the request — including values the platform is
 * responsible for keeping out of logs.
 */
public class TransientCheckFailure extends RuntimeException {

    private final String errorCode;

    public TransientCheckFailure(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public TransientCheckFailure(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
