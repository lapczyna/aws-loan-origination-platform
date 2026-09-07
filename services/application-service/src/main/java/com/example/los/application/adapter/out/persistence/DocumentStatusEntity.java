package com.example.los.application.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A row in the local projection of document status. */
@Entity
@Table(name = "document_status", schema = "application")
class DocumentStatusEntity {

    @Id
    @Column(name = "document_id", nullable = false, updatable = false)
    private UUID documentId;

    @Column(name = "application_id", nullable = false)
    private UUID applicationId;

    @Column(name = "document_type", nullable = false, length = 32)
    private String documentType;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "source_version", nullable = false)
    private long sourceVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentStatusEntity() {}

    DocumentStatusEntity(
            UUID documentId,
            UUID applicationId,
            String documentType,
            String status,
            long sourceVersion,
            Instant updatedAt) {
        this.documentId = documentId;
        this.applicationId = applicationId;
        this.documentType = documentType;
        this.status = status;
        this.sourceVersion = sourceVersion;
        this.updatedAt = updatedAt;
    }

    /**
     * Applies a newer fact about this document.
     *
     * <p>Refuses to move backwards. Under at-least-once delivery an older event
     * can arrive after a newer one, and applying it would turn a CLEAN document
     * back into SCANNING and block a legitimate submission.
     *
     * @return true when the projection was advanced
     */
    boolean applyIfNewer(String newStatus, long version, Instant at) {
        if (version <= sourceVersion) {
            return false;
        }
        this.status = newStatus;
        this.sourceVersion = version;
        this.updatedAt = at;
        return true;
    }

    UUID getDocumentId() {
        return documentId;
    }

    String getDocumentType() {
        return documentType;
    }

    String getStatus() {
        return status;
    }

    long getSourceVersion() {
        return sourceVersion;
    }

    @Override
    public String toString() {
        return "DocumentStatusEntity[documentId=" + documentId + ", status=" + status + "]";
    }
}
