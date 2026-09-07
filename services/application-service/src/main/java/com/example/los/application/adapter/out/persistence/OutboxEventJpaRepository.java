package com.example.los.application.adapter.out.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository over the outbox. */
interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, UUID> {

    /**
     * Claims a batch of events for publication.
     *
     * <p>Written as a native query so that {@code FOR UPDATE SKIP LOCKED} is
     * explicit rather than expressed through a JPA lock hint whose meaning has
     * changed between Hibernate versions. This clause is the reason more than one
     * replica can run the publisher concurrently: each transaction locks the rows
     * it takes and skips rows another replica already holds, so batches never
     * overlap and no replica blocks behind another. Without SKIP LOCKED a second
     * publisher would serialise behind the first and the outbox would drain at
     * single-replica speed no matter how many pods were running.
     *
     * <p>Ordering by {@code created_at} preserves per-aggregate event order.
     * Combined with the application identifier as the Kafka partition key, that
     * gives consumers ordered delivery per application.
     *
     * <p>The timestamp parameter is an {@link OffsetDateTime} rather than an
     * {@code Instant}: the PostgreSQL JDBC driver cannot infer a SQL type for an
     * {@code Instant} bound to a native query and fails with "Can't infer the SQL
     * type to use". Entity mappings and JPQL handle {@code Instant} fine, so this
     * applies only to native queries. Everything here is UTC, so the conversion
     * is lossless.
     */
    @Query(
            value =
                    """
                    SELECT * FROM application.outbox_event
                    WHERE status = 'PENDING' AND next_attempt_at <= :now
                    ORDER BY created_at ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<OutboxEventEntity> claimBatch(@Param("now") OffsetDateTime now, @Param("batchSize") int batchSize);


    long countByStatus(String status);

    /** Backs the "oldest unpublished outbox record" gauge and its alarm. */
    @Query("select min(e.createdAt) from OutboxEventEntity e where e.status <> 'PUBLISHED'")
    Instant oldestUnpublishedCreatedAt();

    List<OutboxEventEntity> findByAggregateIdOrderByCreatedAtAsc(String aggregateId);
}
