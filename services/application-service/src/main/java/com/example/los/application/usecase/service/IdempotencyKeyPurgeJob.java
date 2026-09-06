package com.example.los.application.usecase.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.los.application.usecase.port.IdempotencyStore;

/**
 * Removes idempotency keys past their retention window.
 *
 * <p>The ledger grows by one row per state-changing request forever unless
 * something removes them. Keys are only useful for as long as a client might
 * still retry, so anything past the retention window is dead weight that slows
 * every lookup and inflates every backup.
 *
 * <p>Runs on a schedule and never on the request path: a client's request must
 * not pay for housekeeping. Every replica runs it, which is harmless — the
 * delete is idempotent, and the second replica simply finds nothing to remove.
 */
@Component
class IdempotencyKeyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyKeyPurgeJob.class);

    private final IdempotencyStore store;

    IdempotencyKeyPurgeJob(IdempotencyStore store) {
        this.store = store;
    }

    @Scheduled(fixedDelayString = "${los.idempotency.purge-interval:PT1H}")
    void purgeExpiredKeys() {
        int removed = store.purgeExpired();
        if (removed > 0) {
            log.info("Purged expired idempotency keys. removed={}", removed);
        }
    }
}
