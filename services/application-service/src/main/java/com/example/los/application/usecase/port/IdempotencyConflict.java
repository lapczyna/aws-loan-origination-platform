package com.example.los.application.usecase.port;

import com.example.los.application.domain.exception.DomainException;

/**
 * Raised when an idempotency key is reused with a different request body.
 *
 * <p>This is a client defect: the key is supposed to identify one logical
 * operation. Returning the first response would silently discard the second
 * request, so the API returns 409 Conflict instead.
 *
 * <p>The message deliberately contains neither request body nor fingerprint.
 */
public class IdempotencyConflict extends DomainException {

    public static final String ERROR_CODE = "IDEMPOTENCY_KEY_REUSED";

    public IdempotencyConflict() {
        super(ERROR_CODE, "This idempotency key was already used with a different request body");
    }
}
