package com.example.los.audit.adapter.out.persistence;

import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository over the de-duplication ledger. */
interface AuditProcessedEventJpaRepository extends JpaRepository<AuditProcessedEventEntity, AuditProcessedEventEntity.Key> {

    /**
     * Claims an event for processing.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than an insert wrapped in a
     * try/catch, because in PostgreSQL a constraint violation aborts the entire
     * transaction: catching the exception and carrying on would leave the caller
     * holding a transaction in which every subsequent statement fails with
     * "current transaction is aborted". This form reports the duplicate through
     * the affected-row count and leaves the transaction usable.
     *
     * @return 1 when the event was claimed, 0 when it had already been processed
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO audit.processed_event (event_id, consumer, processed_at)
                    VALUES (:eventId, :consumer, :processedAt)
                    ON CONFLICT (event_id, consumer) DO NOTHING
                    """,
            nativeQuery = true)
    int claim(
            @Param("eventId") String eventId,
            @Param("consumer") String consumer,
            @Param("processedAt") OffsetDateTime processedAt);
}
