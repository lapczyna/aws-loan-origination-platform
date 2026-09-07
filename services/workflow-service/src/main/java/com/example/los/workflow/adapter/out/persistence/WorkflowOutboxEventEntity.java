package com.example.los.workflow.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A row in the workflow context's transactional outbox.
 *
 * <p>Structurally identical to the application context's outbox row, and
 * deliberately a separate type rather than a shared library class. Each bounded
 * context owns its own schema and must be deployable without waiting on a shared
 * release: a change one context needs in its outbox would otherwise force a
 * coordinated deployment of every service. The duplication is small, stable and
 * bounded; the coupling it avoids is neither.
 *
 * <p>Holds the complete serialised envelope, exactly as it will be published, so
 * a later change to the mapping code cannot retroactively alter an event that is
 * already waiting to be sent.
 */
@Entity
@Table(name = "outbox_event", schema = "workflow")
class WorkflowOutboxEventEntity {

    enum Status {
        /** Waiting to be published, or waiting for its next retry. */
        PENDING,
        /** Acknowledged by the broker. */
        PUBLISHED,
        /** Retry budget exhausted. Requires the dead-letter runbook. */
        FAILED
    }

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Column(name = "topic", nullable = false, length = 128)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 64)
    private String partitionKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "correlation_id", nullable = false, length = 64)
    private String correlationId;

    @Column(name = "causation_id", length = 64)
    private String causationId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error_code", length = 64)
    private String lastErrorCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected WorkflowOutboxEventEntity() {}

    @SuppressWarnings("checkstyle:ParameterNumber")
    WorkflowOutboxEventEntity(
            UUID id,
            String aggregateType,
            String aggregateId,
            long aggregateVersion,
            String eventType,
            int schemaVersion,
            String topic,
            String partitionKey,
            String payload,
            String correlationId,
            String causationId,
            Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.correlationId = correlationId;
        this.causationId = causationId;
        this.status = Status.PENDING.name();
        this.attempts = 0;
        this.nextAttemptAt = createdAt;
        this.createdAt = createdAt;
    }

    void markPublished(Instant at) {
        this.status = Status.PUBLISHED.name();
        this.publishedAt = at;
        this.lastErrorCode = null;
    }

    /**
     * Records a failed attempt and schedules the next one.
     *
     * @param errorCode  a safe, stable code; never an exception message, which
     *                   could contain broker addresses or serialised payloads
     * @param retryAt    when to try again
     */
    void markAttemptFailed(String errorCode, Instant retryAt) {
        this.attempts++;
        this.lastErrorCode = errorCode;
        this.nextAttemptAt = retryAt;
    }

    /** Gives up. The row stays for the replay runbook; it is never deleted here. */
    void markPermanentlyFailed(String errorCode) {
        this.attempts++;
        this.status = Status.FAILED.name();
        this.lastErrorCode = errorCode;
    }

    UUID getId() {
        return id;
    }

    String getEventType() {
        return eventType;
    }

    String getTopic() {
        return topic;
    }

    String getPartitionKey() {
        return partitionKey;
    }

    String getPayload() {
        return payload;
    }

    String getAggregateId() {
        return aggregateId;
    }

    long getAggregateVersion() {
        return aggregateVersion;
    }

    String getCorrelationId() {
        return correlationId;
    }

    int getAttempts() {
        return attempts;
    }

    String getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    String getLastErrorCode() {
        return lastErrorCode;
    }

    /**
     * Redacted: the payload is not printed. Even though event payloads carry no
     * personal data by contract, a log line containing a full serialised event is
     * noise at best and a contract violation waiting to happen at worst.
     */
    @Override
    public String toString() {
        return "WorkflowOutboxEventEntity[id=" + id + ", type=" + eventType + ", status=" + status + ", attempts=" + attempts
                + "]";
    }
}
