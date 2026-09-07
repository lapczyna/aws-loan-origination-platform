package com.example.los.application.adapter.out.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.application.usecase.port.IdempotencyKeyAlreadyUsed;
import com.example.los.application.usecase.port.IdempotencyStore;

/**
 * Idempotency ledger backed by PostgreSQL.
 *
 * <p>The write runs with {@link Propagation#MANDATORY} for the same reason the
 * outbox writer does: the record that "this request already happened" and the
 * effect of the request must commit together. Stored in its own transaction, a
 * crash in between would leave a key claiming an outcome that was rolled back,
 * and the client's retry would be answered with a success that never occurred.
 */
@Component
class JpaIdempotencyStore implements IdempotencyStore {

    private final IdempotencyKeyJpaRepository keys;
    private final Clock clock;
    private final Duration retention;

    JpaIdempotencyStore(
            IdempotencyKeyJpaRepository keys,
            Clock clock,
            @Value("${los.idempotency.retention:PT24H}") Duration retention) {
        this.keys = keys;
        this.clock = clock;
        this.retention = retention;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredResponse> find(String idempotencyKey, String operation) {
        return keys.findById(new IdempotencyKeyEntity.Key(idempotencyKey, operation))
                .map(entity -> new StoredResponse(
                        entity.getRequestFingerprint(), entity.getResponseStatus(), entity.getResponseBody()));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void store(
            String idempotencyKey,
            String operation,
            String requestFingerprint,
            String applicationId,
            int responseStatus,
            String responseBody) {
        Instant now = clock.instant();
        int claimed = keys.claim(
                idempotencyKey,
                operation,
                requestFingerprint,
                applicationId,
                responseStatus,
                responseBody,
                OffsetDateTime.ofInstant(now, ZoneOffset.UTC),
                OffsetDateTime.ofInstant(now.plus(retention), ZoneOffset.UTC));

        if (claimed == 0) {
            // Two identical requests raced and both got past the lookup. The
            // uniqueness constraint picks the winner; the loser must not also
            // apply its effect, so throwing here rolls its whole transaction back
            // and the caller re-reads the winner's stored response.
            throw new IdempotencyKeyAlreadyUsed(idempotencyKey);
        }
    }

    @Override
    @Transactional
    public int purgeExpired() {
        return keys.deleteExpired(clock.instant());
    }
}
