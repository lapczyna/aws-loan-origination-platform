package com.example.los.document.adapter.out.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.document.domain.model.Document;
import com.example.los.document.domain.model.DocumentId;
import com.example.los.document.domain.model.DocumentStatus;
import com.example.los.document.domain.model.ObjectLocation;
import com.example.los.document.domain.model.StoredObject;
import com.example.los.document.usecase.port.ConcurrentDocumentUpdate;
import com.example.los.document.usecase.port.DocumentRepository;
import com.example.los.events.vocabulary.DocumentType;

/** Persistence adapter for document metadata. */
@Repository
class JpaDocumentRepository implements DocumentRepository {

    private final DocumentJpaRepository documents;

    JpaDocumentRepository(DocumentJpaRepository documents) {
        this.documents = documents;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Document> findById(DocumentId id) {
        return documents.findById(id.value()).map(JpaDocumentRepository::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Document> findByApplicationId(String applicationId) {
        return documents.findByApplicationIdOrderByCreatedAtAsc(UUID.fromString(applicationId)).stream()
                .map(JpaDocumentRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public Document save(Document document) {
        DocumentEntity entity = documents
                .findById(document.id().value())
                .orElseGet(() -> new DocumentEntity(
                        document.id().value(), UUID.fromString(document.applicationId())));

        applyTo(entity, document);

        try {
            documents.saveAndFlush(entity);
        } catch (OptimisticLockingFailureException | DataIntegrityViolationException e) {
            throw new ConcurrentDocumentUpdate(document.id());
        }
        return document;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Document> claimAwaitingScan(int batchSize) {
        return documents.claimAwaitingScan(batchSize).stream()
                .map(JpaDocumentRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Document> findAbandonedUploads(Instant now, int batchSize) {
        return documents.findAbandoned(OffsetDateTime.ofInstant(now, ZoneOffset.UTC), batchSize).stream()
                .map(JpaDocumentRepository::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long countByStatus(DocumentStatus status) {
        return documents.countByStatus(status.name());
    }

    private static void applyTo(DocumentEntity entity, Document document) {
        entity.setDocumentType(document.type().name());
        entity.setStatus(document.status().name());
        entity.setBucket(document.location().bucket());
        entity.setObjectKey(document.location().objectKey());
        entity.setDeclaredContentType(document.declaredContentType());
        entity.setDeclaredSizeBytes(document.declaredSizeBytes());

        StoredObject stored = document.stored();
        entity.setStoredSizeBytes(stored == null ? null : stored.sizeBytes());
        entity.setStoredETag(stored == null ? null : stored.eTag());
        entity.setStoredChecksumSha256(stored == null ? null : stored.checksumSha256());

        entity.setScanReasonCode(document.scanReasonCode());
        entity.setAggregateVersion(document.version());
        entity.setCreatedAt(document.createdAt());
        entity.setUpdatedAt(document.updatedAt());
        entity.setUploadExpiresAt(document.uploadExpiresAt());
    }

    private static Document toDomain(DocumentEntity entity) {
        StoredObject stored = entity.getStoredSizeBytes() == null
                ? null
                : new StoredObject(
                        entity.getStoredSizeBytes(),
                        entity.getDeclaredContentType(),
                        entity.getStoredETag(),
                        entity.getStoredChecksumSha256());

        return Document.reconstitute(
                new DocumentId(entity.getId()),
                entity.getApplicationId().toString(),
                DocumentType.valueOf(entity.getDocumentType()),
                new ObjectLocation(entity.getBucket(), entity.getObjectKey()),
                entity.getDeclaredContentType(),
                entity.getDeclaredSizeBytes(),
                DocumentStatus.valueOf(entity.getStatus()),
                stored,
                entity.getScanReasonCode(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getUploadExpiresAt(),
                entity.getAggregateVersion());
    }
}
