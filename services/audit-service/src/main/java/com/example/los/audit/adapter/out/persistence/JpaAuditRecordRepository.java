package com.example.los.audit.adapter.out.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import com.example.los.audit.domain.model.AuditRecord;
import com.example.los.audit.usecase.port.AuditRecordRepository;
import com.example.los.audit.usecase.port.DuplicateAuditRecordException;
import com.example.los.events.EventJson;

/**
 * Persistence adapter for the audit trail.
 *
 * <p>Implements {@code append} and reads, and nothing else. There is no update
 * path and no delete path anywhere in this class, which is the code-level half of
 * the append-only guarantee; the database grants are the other half.
 */
@Repository
class JpaAuditRecordRepository implements AuditRecordRepository {

    private static final TypeReference<Map<String, String>> SUMMARY_TYPE = new TypeReference<>() {};

    private final AuditRecordJpaRepository records;

    JpaAuditRecordRepository(AuditRecordJpaRepository records) {
        this.records = records;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AuditRecord> findLastInChain(String chainKey) {
        return records.findTopByChainKeyOrderBySequenceNumberDesc(chainKey).map(JpaAuditRecordRepository::toDomain);
    }

    @Override
    @Transactional
    public AuditRecord append(AuditRecord record) {
        try {
            records.saveAndFlush(new AuditRecordEntity(
                    record.chainKey(),
                    record.sequenceNumber(),
                    record.id(),
                    record.sourceEventId(),
                    record.eventType(),
                    record.schemaVersion(),
                    record.aggregateType(),
                    record.aggregateId(),
                    record.aggregateVersion(),
                    record.subjectReference(),
                    EventJson.write(record.summary()),
                    record.correlationId(),
                    record.occurredAt(),
                    record.recordedAt(),
                    record.previousHash(),
                    record.recordHash()));
            return record;

        } catch (DataIntegrityViolationException e) {
            // Either the same source event was already recorded in this chain, or
            // two deliveries raced for the same sequence number. Both mean the
            // same thing here: another writer got there first and this record
            // must not be appended. Throwing rolls the caller's transaction back
            // cleanly, which is what the caller expects.
            throw new DuplicateAuditRecordException(record.chainKey(), record.sourceEventId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditRecord> findChain(String chainKey) {
        return records.findByChainKeyOrderBySequenceNumberAsc(chainKey).stream()
                .map(JpaAuditRecordRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long count() {
        return records.count();
    }

    private static AuditRecord toDomain(AuditRecordEntity entity) {
        return new AuditRecord(
                entity.getId(),
                entity.getChainKey(),
                entity.getSequenceNumber(),
                entity.getSourceEventId(),
                entity.getEventType(),
                entity.getSchemaVersion(),
                entity.getAggregateType(),
                entity.getAggregateId(),
                entity.getAggregateVersion(),
                entity.getSubjectReference(),
                EventJson.mapper().readValue(entity.getSummary(), SUMMARY_TYPE),
                entity.getCorrelationId(),
                entity.getOccurredAt(),
                entity.getRecordedAt(),
                entity.getPreviousHash(),
                entity.getRecordHash());
    }
}
