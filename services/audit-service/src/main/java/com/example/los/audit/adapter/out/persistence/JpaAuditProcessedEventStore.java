package com.example.los.audit.adapter.out.persistence;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.audit.usecase.port.ProcessedEventStore;

/**
 * De-duplication ledger backed by PostgreSQL.
 *
 * <p>Runs inside the consumer's transaction, so the "already seen" record and the
 * business effect commit as one unit. A duplicate is normal traffic under
 * at-least-once delivery, not an exceptional condition, so it is reported as a
 * return value rather than an exception.
 */
@Component
class JpaAuditProcessedEventStore implements ProcessedEventStore {

    private final AuditProcessedEventJpaRepository processed;
    private final Clock clock;

    JpaAuditProcessedEventStore(AuditProcessedEventJpaRepository processed, Clock clock) {
        this.processed = processed;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markProcessed(String eventId, String consumer) {
        return processed.claim(eventId, consumer, OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)) == 1;
    }
}
