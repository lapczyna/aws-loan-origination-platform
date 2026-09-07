package com.example.los.document.adapter.out.persistence;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The publisher's view of the outbox.
 *
 * <p>Exists so the messaging adapter can drain the outbox without importing JPA
 * entities. {@link PendingEvent} is a flat, immutable projection carrying only
 * what a publisher needs; the entity itself never leaves this package.
 *
 * <p>The mutating methods require an existing transaction, because the row lock
 * taken by {@link #claimPending} must be held until the batch is finished.
 */
@Component
public class DocumentOutboxRelayGateway {

    /**
     * An event waiting to be published.
     *
     * @param id           the event identifier, also the consumer's de-duplication key
     * @param eventType    logical event type, safe to log
     * @param topic        destination topic
     * @param partitionKey Kafka partition key; always the application identifier
     * @param payload      the complete serialised envelope, published verbatim
     * @param attempts     how many attempts have already failed
     */
    public record PendingEvent(
            UUID id, String eventType, String topic, String partitionKey, String payload, int attempts) {

        /** Redacted: the payload is never printed, even though it carries no personal data by contract. */
        @Override
        public String toString() {
            return "PendingEvent[id=" + id + ", type=" + eventType + ", attempts=" + attempts + "]";
        }
    }

    private final DocumentOutboxJpaRepository outbox;
    private final Clock clock;

    DocumentOutboxRelayGateway(DocumentOutboxJpaRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Locks and returns up to {@code batchSize} events that are due for publication. */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<PendingEvent> claimPending(int batchSize) {
        return outbox.claimBatch(OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), batchSize).stream()
                .map(e -> new PendingEvent(
                        e.getId(), e.getEventType(), e.getTopic(), e.getPartitionKey(), e.getPayload(), e.getAttempts()))
                .toList();
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markPublished(UUID eventId, Instant at) {
        outbox.findById(eventId).ifPresent(e -> e.markPublished(at));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markAttemptFailed(UUID eventId, String errorCode, Instant retryAt) {
        outbox.findById(eventId).ifPresent(e -> e.markAttemptFailed(errorCode, retryAt));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void markPermanentlyFailed(UUID eventId, String errorCode) {
        outbox.findById(eventId).ifPresent(e -> e.markPermanentlyFailed(errorCode));
    }

    // --- Observability -------------------------------------------------------

    /** Backs the outbox-backlog gauge and its alarm. */
    @Transactional(readOnly = true)
    public long pendingCount() {
        return outbox.countByStatus(DocumentOutboxEventEntity.Status.PENDING.name());
    }

    /** Backs the dead-letter alarm: events that exhausted their retry budget. */
    @Transactional(readOnly = true)
    public long permanentlyFailedCount() {
        return outbox.countByStatus(DocumentOutboxEventEntity.Status.FAILED.name());
    }

    /**
     * Age of the oldest unpublished event.
     *
     * <p>The single most useful outbox signal. Backlog size alone is ambiguous —
     * a large backlog draining quickly is healthy — but a growing oldest-record
     * age means events are stuck, which is a customer-visible problem.
     */
    @Transactional(readOnly = true)
    public Optional<Instant> oldestUnpublishedAt() {
        return Optional.ofNullable(outbox.oldestUnpublishedCreatedAt());
    }
}
