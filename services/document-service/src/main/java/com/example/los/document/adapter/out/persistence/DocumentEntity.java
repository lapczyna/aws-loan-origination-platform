package com.example.los.document.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * JPA mapping of document metadata.
 *
 * <p>Metadata only: no file content ever reaches this row, and nothing here is
 * personal data. Both the declared and the stored attributes are kept, because
 * comparing them is what verifies that the object which arrived is the one the
 * client asked permission to upload.
 */
@Entity
@Table(name = "document", schema = "document")
class DocumentEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "application_id", nullable = false, updatable = false)
    private UUID applicationId;

    @Column(name = "document_type", nullable = false, length = 32, updatable = false)
    private String documentType;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "bucket", nullable = false, length = 255, updatable = false)
    private String bucket;

    @Column(name = "object_key", nullable = false, length = 512, updatable = false)
    private String objectKey;

    // What the client said it would upload.
    @Column(name = "declared_content_type", nullable = false, length = 128, updatable = false)
    private String declaredContentType;

    @Column(name = "declared_size_bytes", updatable = false)
    private Long declaredSizeBytes;

    // What S3 actually reported.
    @Column(name = "stored_size_bytes")
    private Long storedSizeBytes;

    @Column(name = "stored_etag", length = 128)
    private String storedETag;

    @Column(name = "stored_checksum_sha256", length = 64)
    private String storedChecksumSha256;

    @Column(name = "scan_reason_code", length = 64)
    private String scanReasonCode;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "upload_expires_at", nullable = false, updatable = false)
    private Instant uploadExpiresAt;

    protected DocumentEntity() {}

    DocumentEntity(UUID id, UUID applicationId) {
        this.id = id;
        this.applicationId = applicationId;
    }

    UUID getId() {
        return id;
    }

    UUID getApplicationId() {
        return applicationId;
    }

    String getDocumentType() {
        return documentType;
    }

    void setDocumentType(String documentType) {
        this.documentType = documentType;
    }

    String getStatus() {
        return status;
    }

    void setStatus(String status) {
        this.status = status;
    }

    String getBucket() {
        return bucket;
    }

    void setBucket(String bucket) {
        this.bucket = bucket;
    }

    String getObjectKey() {
        return objectKey;
    }

    void setObjectKey(String objectKey) {
        this.objectKey = objectKey;
    }

    String getDeclaredContentType() {
        return declaredContentType;
    }

    void setDeclaredContentType(String declaredContentType) {
        this.declaredContentType = declaredContentType;
    }

    Long getDeclaredSizeBytes() {
        return declaredSizeBytes;
    }

    void setDeclaredSizeBytes(Long declaredSizeBytes) {
        this.declaredSizeBytes = declaredSizeBytes;
    }

    Long getStoredSizeBytes() {
        return storedSizeBytes;
    }

    void setStoredSizeBytes(Long storedSizeBytes) {
        this.storedSizeBytes = storedSizeBytes;
    }

    String getStoredETag() {
        return storedETag;
    }

    void setStoredETag(String storedETag) {
        this.storedETag = storedETag;
    }

    String getStoredChecksumSha256() {
        return storedChecksumSha256;
    }

    void setStoredChecksumSha256(String storedChecksumSha256) {
        this.storedChecksumSha256 = storedChecksumSha256;
    }

    String getScanReasonCode() {
        return scanReasonCode;
    }

    void setScanReasonCode(String scanReasonCode) {
        this.scanReasonCode = scanReasonCode;
    }

    long getAggregateVersion() {
        return aggregateVersion;
    }

    void setAggregateVersion(long aggregateVersion) {
        this.aggregateVersion = aggregateVersion;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }

    void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    Instant getUploadExpiresAt() {
        return uploadExpiresAt;
    }

    void setUploadExpiresAt(Instant uploadExpiresAt) {
        this.uploadExpiresAt = uploadExpiresAt;
    }

    @Override
    public String toString() {
        return "DocumentEntity[id=" + id + ", type=" + documentType + ", status=" + status + "]";
    }
}
