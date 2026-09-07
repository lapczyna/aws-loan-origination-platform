package com.example.los.document.domain.model;

/**
 * The object that arrived in S3 does not match what the client declared.
 *
 * <p>Treated as a security event, not a validation slip. A presigned URL is a
 * bearer credential to write one key, and a mismatch means the holder uploaded
 * something other than what they asked permission for.
 *
 * <p>The message carries a stable code and the constraint that was violated,
 * never the offending values.
 */
public class UploadVerificationFailedException extends RuntimeException {

    private final String errorCode;

    public UploadVerificationFailedException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
