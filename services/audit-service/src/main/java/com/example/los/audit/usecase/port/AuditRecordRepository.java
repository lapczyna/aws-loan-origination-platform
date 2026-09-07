package com.example.los.audit.usecase.port;

import java.util.List;
import java.util.Optional;

import com.example.los.audit.domain.model.AuditRecord;

/**
 * Outbound port for the audit trail.
 *
 * <p>There is deliberately no {@code update} and no {@code delete}. The trail is
 * append-only, and the absence of those methods is the first line of enforcement;
 * the database grants in {@code V2__append_only_grants.sql} are the second.
 */
public interface AuditRecordRepository {

    /**
     * The most recent record in a chain, which the next record hashes onto.
     *
     * @return empty when the chain has no records yet
     */
    Optional<AuditRecord> findLastInChain(String chainKey);

    /**
     * Appends a record.
     *
     * @throws DuplicateAuditRecordException when this source event was already
     *         recorded in this chain, which under at-least-once delivery is
     *         normal traffic rather than an error
     */
    AuditRecord append(AuditRecord record);

    /** Every record in a chain, in sequence order. Used to verify the chain. */
    List<AuditRecord> findChain(String chainKey);

    long count();
}
