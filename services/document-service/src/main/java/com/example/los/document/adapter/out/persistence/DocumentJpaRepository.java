package com.example.los.document.adapter.out.persistence;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository over document metadata. */
interface DocumentJpaRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findByApplicationIdOrderByCreatedAtAsc(UUID applicationId);

    long countByStatus(String status);

    /**
     * Claims documents waiting to be scanned.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} so several replicas can scan
     * concurrently without two of them scanning the same object — which, against
     * a scanner billed per object, is a duplicated charge as well as wasted work.
     */
    @Query(
            value =
                    """
                    SELECT * FROM document.document
                    WHERE status = 'SCANNING'
                    ORDER BY updated_at ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<DocumentEntity> claimAwaitingScan(@Param("batchSize") int batchSize);

    /**
     * Uploads whose presigned URL expired without an object arriving.
     *
     * <p>The timestamp is an {@link OffsetDateTime} because the PostgreSQL JDBC
     * driver cannot infer a SQL type for an {@code Instant} in a native query.
     */
    @Query(
            value =
                    """
                    SELECT * FROM document.document
                    WHERE status = 'PENDING_UPLOAD' AND upload_expires_at < :now
                    ORDER BY upload_expires_at ASC
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<DocumentEntity> findAbandoned(@Param("now") OffsetDateTime now, @Param("batchSize") int batchSize);
}
