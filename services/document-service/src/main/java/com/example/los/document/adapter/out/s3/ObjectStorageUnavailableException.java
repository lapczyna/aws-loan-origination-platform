package com.example.los.document.adapter.out.s3;

/**
 * The object store could not be reached, or refused the request.
 *
 * <p>Deliberately does not carry the AWS SDK's own message. That message quotes
 * the request it failed on — bucket, key and headers — and in the presigning path
 * it can include the signed URL itself. The status code is enough to diagnose
 * with, and it is safe.
 */
public class ObjectStorageUnavailableException extends RuntimeException {

    public static final String ERROR_CODE = "OBJECT_STORAGE_UNAVAILABLE";

    public ObjectStorageUnavailableException(String message, Throwable cause) {
        // The cause is retained for the stack trace but its message is never
        // surfaced to a caller: see ApiExceptionHandler.
        super(message, cause);
    }

    public String errorCode() {
        return ERROR_CODE;
    }
}
