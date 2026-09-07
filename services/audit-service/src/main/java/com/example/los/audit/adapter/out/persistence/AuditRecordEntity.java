package com.example.los.audit.adapter.out.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA mapping of an audit record.
 *
 * <p>Marked {@link Immutable}, which tells Hibernate the row will never be
 * updated. That is a third layer of append-only enforcement, alongside the port
 * having no update method and the runtime role having no UPDATE grant. Each layer
 * catches a different mistake: the port catches a developer reaching for the
 * wrong method, this annotation catches a dirty-checked change nobody intended,
 * and the grant catches everything else including raw SQL.
 *
 * <p>Every field is {@code updatable = false} for the same reason.
 */
@Entity
@Table(name = "audit_record", schema = "audit")
@IdClass(AuditRecordEntity.Key.class)
@Immutable
class AuditRecordEntity {

    /**
     * Composite key: one record per sequence number per chain.
     *
     * <p>A class, not a record: JPA requires an {@code @IdClass} to be a public
     * class with a public no-argument constructor.
     */
    public static class Key implements Serializable {

        private String chainKey;
        private long sequenceNumber;

        public Key() {}

        public Key(String chainKey, long sequenceNumber) {
            this.chainKey = chainKey;
            this.sequenceNumber = sequenceNumber;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key that)) {
                return false;
            }
            return sequenceNumber == that.sequenceNumber && Objects.equals(chainKey, that.chainKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chainKey, sequenceNumber);
        }
    }

    @Id
    @Column(name = "chain_key", nullable = false, length = 64, updatable = false)
    private String chainKey;

    @Id
    @Column(name = "sequence_number", nullable = false, updatable = false)
    private long sequenceNumber;

    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "source_event_id", nullable = false, length = 64, updatable = false)
    private String sourceEventId;

    @Column(name = "event_type", nullable = false, length = 64, updatable = false)
    private String eventType;

    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion;

    @Column(name = "aggregate_type", nullable = false, length = 64, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64, updatable = false)
    private String aggregateId;

    @Column(name = "aggregate_version", nullable = false, updatable = false)
    private long aggregateVersion;

    @Column(name = "subject_reference", length = 64, updatable = false)
    private String subjectReference;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "summary", nullable = false, columnDefinition = "jsonb", updatable = false)
    private String summary;

    @Column(name = "correlation_id", nullable = false, length = 64, updatable = false)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "previous_hash", length = 64, updatable = false)
    private String previousHash;

    @Column(name = "record_hash", nullable = false, length = 64, updatable = false)
    private String recordHash;

    protected AuditRecordEntity() {}

    @SuppressWarnings("checkstyle:ParameterNumber")
    AuditRecordEntity(
            String chainKey,
            long sequenceNumber,
            UUID id,
            String sourceEventId,
            String eventType,
            int schemaVersion,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String subjectReference,
            String summary,
            String correlationId,
            Instant occurredAt,
            Instant recordedAt,
            String previousHash,
            String recordHash) {
        this.chainKey = chainKey;
        this.sequenceNumber = sequenceNumber;
        this.id = id;
        this.sourceEventId = sourceEventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
        this.subjectReference = subjectReference;
        this.summary = summary;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
        this.recordedAt = recordedAt;
        this.previousHash = previousHash;
        this.recordHash = recordHash;
    }

    String getChainKey() {
        return chainKey;
    }

    long getSequenceNumber() {
        return sequenceNumber;
    }

    UUID getId() {
        return id;
    }

    String getSourceEventId() {
        return sourceEventId;
    }

    String getEventType() {
        return eventType;
    }

    int getSchemaVersion() {
        return schemaVersion;
    }

    String getAggregateType() {
        return aggregateType;
    }

    String getAggregateId() {
        return aggregateId;
    }

    long getAggregateVersion() {
        return aggregateVersion;
    }

    String getSubjectReference() {
        return subjectReference;
    }

    String getSummary() {
        return summary;
    }

    String getCorrelationId() {
        return correlationId;
    }

    Instant getOccurredAt() {
        return occurredAt;
    }

    Instant getRecordedAt() {
        return recordedAt;
    }

    String getPreviousHash() {
        return previousHash;
    }

    String getRecordHash() {
        return recordHash;
    }

    @Override
    public String toString() {
        return "AuditRecordEntity[chainKey=" + chainKey + ", sequence=" + sequenceNumber + "]";
    }
}
