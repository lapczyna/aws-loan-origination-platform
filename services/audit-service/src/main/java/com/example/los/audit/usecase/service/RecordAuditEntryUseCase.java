package com.example.los.audit.usecase.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.audit.domain.model.AuditRecord;
import com.example.los.audit.domain.model.AuditSummary;
import com.example.los.audit.usecase.port.AuditRecordRepository;
import com.example.los.audit.usecase.port.DuplicateAuditRecordException;
import com.example.los.audit.usecase.port.ProcessedEventStore;

/**
 * Appends an event to the audit trail.
 *
 * <h2>Why the chain is per aggregate</h2>
 *
 * <p>A single global chain would serialise every write on the platform: two
 * events arriving at once would contend for the same tail record, and throughput
 * would be capped at one audit write at a time regardless of how many replicas
 * were running. Chaining per aggregate keeps writes for different applications
 * independent while still giving an auditor a verifiable, ordered history of the
 * one application they are asking about — which is the question auditors actually
 * ask.
 *
 * <h2>Duplicates</h2>
 *
 * <p>Guarded twice. The de-duplication ledger short-circuits before any hash is
 * computed, and the unique constraint on {@code (chain_key, source_event_id)}
 * catches anything that races past it. Both are needed: the first is cheap and
 * handles the common case, the second is authoritative and handles the race.
 *
 * <p>A duplicate must not append. A second record for the same event would extend
 * the chain with an entry an auditor could not distinguish from a genuine
 * repeated action.
 */
@Service
public class RecordAuditEntryUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecordAuditEntryUseCase.class);
    private static final String CONSUMER = "audit-service.all-events";

    private final AuditRecordRepository records;
    private final ProcessedEventStore processedEvents;
    private final Clock clock;

    public RecordAuditEntryUseCase(
            AuditRecordRepository records, ProcessedEventStore processedEvents, Clock clock) {
        this.records = records;
        this.processedEvents = processedEvents;
        this.clock = clock;
    }

    /** What happened, for metrics and for tests. */
    public enum Outcome {
        RECORDED,
        DUPLICATE_IGNORED
    }

    /**
     * The facts to record, already reduced to safe values by the inbound adapter.
     *
     * @param subjectReference pseudonymous applicant reference, never a name or
     *                         an email address
     * @param summary          allow-listed key/value pairs; see {@link AuditSummary}
     */
    public record AuditEntry(
            String sourceEventId,
            String eventType,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String subjectReference,
            Map<String, String> summary,
            String correlationId,
            Instant occurredAt) {

        public AuditEntry {
            summary = summary == null ? Map.of() : Map.copyOf(summary);
        }

        /** Redacted: an audit entry reaching a log duplicates the trail into a less protected store. */
        @Override
        public String toString() {
            return "AuditEntry[eventType=" + eventType + ", aggregateId=" + aggregateId + "]";
        }
    }

    @Transactional
    public Outcome record(AuditEntry entry) {
        if (!processedEvents.markProcessed(entry.sourceEventId(), CONSUMER)) {
            log.debug("Duplicate event ignored by the audit trail. eventId={}", entry.sourceEventId());
            return Outcome.DUPLICATE_IGNORED;
        }

        // Rejects anything not on the allow-list, so a new event type cannot
        // quietly introduce personal data into the trail.
        Map<String, String> safeSummary = AuditSummary.requireSafe(entry.summary());

        String chainKey = entry.aggregateId();
        Optional<AuditRecord> previous = records.findLastInChain(chainKey);

        AuditRecord record = AuditRecord.append(
                chainKey,
                previous.orElse(null),
                entry.sourceEventId(),
                entry.eventType(),
                entry.schemaVersion(),
                entry.aggregateType(),
                entry.aggregateId(),
                entry.aggregateVersion(),
                entry.subjectReference(),
                safeSummary,
                entry.correlationId(),
                entry.occurredAt(),
                clock.instant());

        try {
            records.append(record);
        } catch (DuplicateAuditRecordException e) {
            // The ledger and the constraint disagreed, which means two deliveries
            // raced. The winner recorded it; this one must not.
            log.debug("Concurrent duplicate rejected by the audit constraint. eventId={}", entry.sourceEventId());
            return Outcome.DUPLICATE_IGNORED;
        }

        log.info(
                "Audit record appended. chainKey={} sequence={} eventType={} correlationId={}",
                chainKey,
                record.sequenceNumber(),
                entry.eventType(),
                entry.correlationId());

        return Outcome.RECORDED;
    }

    /**
     * Verifies a chain from its first record to its last.
     *
     * <p>Two independent checks, catching different tampering. Recomputing each
     * record's own hash catches an entry altered in place. Checking that each
     * record links to its predecessor catches an entry removed, reordered or
     * inserted — which recomputing hashes alone would not.
     *
     * @return the outcome, naming the first sequence number at which the chain
     *         breaks
     */
    @Transactional(readOnly = true)
    public ChainVerification verifyChain(String chainKey) {
        List<AuditRecord> chain = records.findChain(chainKey);
        if (chain.isEmpty()) {
            return new ChainVerification(chainKey, true, 0, null);
        }

        AuditRecord previous = null;
        for (AuditRecord record : chain) {
            if (!record.hasIntactHash()) {
                return new ChainVerification(chainKey, false, chain.size(), record.sequenceNumber());
            }
            if (!record.follows(previous)) {
                return new ChainVerification(chainKey, false, chain.size(), record.sequenceNumber());
            }
            previous = record;
        }
        return new ChainVerification(chainKey, true, chain.size(), null);
    }

    /**
     * The result of verifying a chain.
     *
     * @param brokenAtSequence the first sequence number that failed, or null when intact
     */
    public record ChainVerification(String chainKey, boolean intact, int recordCount, Long brokenAtSequence) {}
}
