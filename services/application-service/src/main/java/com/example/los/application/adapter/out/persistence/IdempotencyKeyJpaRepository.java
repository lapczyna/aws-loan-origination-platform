package com.example.los.application.adapter.out.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository over the idempotency ledger. */
interface IdempotencyKeyJpaRepository extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyEntity.Key> {

    /**
     * Claims an idempotency key and records the outcome of the request.
     *
     * <p>{@code ON CONFLICT DO NOTHING} for the same reason as the inbox: a
     * caught constraint violation would poison the surrounding transaction in
     * PostgreSQL. Reporting the race through the affected-row count lets the
     * caller roll back cleanly and re-read the winner's stored response.
     *
     * @return 1 when the key was claimed, 0 when a concurrent request won it
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO application.idempotency_key
                        (idempotency_key, operation, request_fingerprint, application_id,
                         response_status, response_body, created_at, expires_at)
                    VALUES (:key, :operation, :fingerprint, CAST(:applicationId AS uuid),
                            :responseStatus, :responseBody, :createdAt, :expiresAt)
                    ON CONFLICT (idempotency_key, operation) DO NOTHING
                    """,
            nativeQuery = true)
    int claim(
            @Param("key") String key,
            @Param("operation") String operation,
            @Param("fingerprint") String fingerprint,
            @Param("applicationId") String applicationId,
            @Param("responseStatus") int responseStatus,
            @Param("responseBody") String responseBody,
            @Param("createdAt") OffsetDateTime createdAt,
            @Param("expiresAt") OffsetDateTime expiresAt);

    /** Removes keys past their retention window. Runs on a schedule, never on the request path. */
    @Modifying
    @Query("delete from IdempotencyKeyEntity e where e.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);
}
