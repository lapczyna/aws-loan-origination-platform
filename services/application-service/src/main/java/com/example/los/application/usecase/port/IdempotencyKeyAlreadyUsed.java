package com.example.los.application.usecase.port;

import com.example.los.application.domain.exception.DomainException;

/**
 * Raised when an idempotency key was claimed by a concurrent request.
 *
 * <p>Distinct from {@link IdempotencyConflict}: this means two identical
 * requests raced, and the loser should re-read the winner's stored response
 * rather than report an error.
 */
public class IdempotencyKeyAlreadyUsed extends DomainException {

    public static final String ERROR_CODE = "IDEMPOTENCY_KEY_IN_USE";

    public IdempotencyKeyAlreadyUsed(String idempotencyKey) {
        super(ERROR_CODE, "Idempotency key was claimed concurrently: length=" + idempotencyKey.length());
    }
}
