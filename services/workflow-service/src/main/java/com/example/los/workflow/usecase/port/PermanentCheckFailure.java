package com.example.los.workflow.usecase.port;

/**
 * The provider refused the request in a way that retrying cannot fix.
 *
 * <p>A malformed request, a revoked credential, an unsupported product. Retrying
 * would waste the budget and delay the applicant without any prospect of a
 * different answer, so the workflow abandons the check immediately.
 */
public class PermanentCheckFailure extends RuntimeException {

    private final String errorCode;

    public PermanentCheckFailure(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
