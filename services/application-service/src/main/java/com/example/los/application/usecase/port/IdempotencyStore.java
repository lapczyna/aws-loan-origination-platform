package com.example.los.application.usecase.port;

import java.util.Optional;

/**
 * Outbound port for idempotent request handling.
 *
 * <p>Gives the API its "safe to retry" guarantee: a client that times out and
 * retries a submission must not create a second application, and must not be
 * told the first one failed.
 */
public interface IdempotencyStore {

    /**
     * A previously stored outcome for an idempotency key.
     *
     * @param requestFingerprint SHA-256 of the canonicalised request that first used the key
     * @param responseStatus     HTTP status originally returned
     * @param responseBody       response body originally returned, replayed verbatim
     */
    record StoredResponse(String requestFingerprint, int responseStatus, String responseBody) {}

    Optional<StoredResponse> find(String idempotencyKey, String operation);

    /**
     * Records the outcome of a request. Must run inside the same transaction as
     * the business effect, so a committed effect always has a recorded outcome
     * and a rolled-back one never does.
     *
     * @throws IdempotencyKeyAlreadyUsed when the key was claimed concurrently by another request
     */
    void store(
            String idempotencyKey,
            String operation,
            String requestFingerprint,
            String applicationId,
            int responseStatus,
            String responseBody);

    /** Removes expired keys. Called on a schedule, never on the request path. */
    int purgeExpired();
}
