package com.example.los.workflow.adapter.out.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository over workflow instances. */
interface WorkflowInstanceJpaRepository extends JpaRepository<WorkflowInstanceEntity, UUID> {

    Optional<WorkflowInstanceEntity> findByApplicationId(UUID applicationId);

    long countByStatusAndStartedAtBefore(String status, Instant threshold);

    /**
     * Leases due workflows to the calling replica and returns their identifiers.
     *
     * <p>One statement, so the select and the lease commit atomically. The inner
     * query takes {@code FOR UPDATE SKIP LOCKED}, which is what stops two
     * replicas leasing the same workflow and calling the same external provider
     * twice — a duplicated charge against a bureau that bills per lookup, not
     * merely a wasted call.
     *
     * <p>Pushing {@code next_attempt_at} forward is the lease itself: a workflow
     * this replica holds is invisible to other pollers until the lease expires,
     * and if this replica dies the lease simply lapses and the work is picked up
     * again.
     *
     * <p>Timestamps are {@link OffsetDateTime} because the PostgreSQL JDBC driver
     * cannot infer a SQL type for an {@code Instant} bound to a native query.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            value =
                    """
                    UPDATE workflow.workflow_instance
                    SET next_attempt_at = :leaseUntil
                    WHERE id IN (
                        SELECT id FROM workflow.workflow_instance
                        WHERE status = 'RUNNING'
                          AND next_attempt_at IS NOT NULL
                          AND next_attempt_at <= :now
                        ORDER BY next_attempt_at ASC
                        LIMIT :batchSize
                        FOR UPDATE SKIP LOCKED
                    )
                    RETURNING id
                    """,
            nativeQuery = true)
    List<UUID> leaseDue(
            @Param("now") OffsetDateTime now,
            @Param("leaseUntil") OffsetDateTime leaseUntil,
            @Param("batchSize") int batchSize);
}
