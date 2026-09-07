package com.example.los.document.usecase.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.example.los.document.domain.model.Document;
import com.example.los.document.domain.model.DocumentId;
import com.example.los.document.domain.model.DocumentStatus;

/** Outbound port for document metadata. */
public interface DocumentRepository {

    Optional<Document> findById(DocumentId id);

    List<Document> findByApplicationId(String applicationId);

    Document save(Document document);

    /** Documents awaiting a scan. Claimed with SKIP LOCKED so replicas cooperate. */
    List<Document> claimAwaitingScan(int batchSize);

    /**
     * Uploads whose presigned URL expired without an object arriving.
     *
     * <p>Swept so an application is not left waiting forever on a document its
     * owner abandoned, and so the metadata row does not outlive its purpose.
     */
    List<Document> findAbandonedUploads(Instant now, int batchSize);

    long countByStatus(DocumentStatus status);
}
