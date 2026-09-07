package com.example.los.document.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.example.los.document.domain.event.DocumentDomainEvent;
import com.example.los.document.domain.event.ScanCompleted;
import com.example.los.document.domain.event.UploadCompleted;
import com.example.los.document.domain.event.UploadRequested;
import com.example.los.events.vocabulary.DocumentType;
import com.example.los.events.vocabulary.ScanOutcome;

/**
 * A supporting document and its quarantine lifecycle.
 *
 * <pre>
 *   PENDING_UPLOAD ──► UPLOADED ──► SCANNING ──► CLEAN
 *                                            └─► REJECTED
 * </pre>
 *
 * <p>The lifecycle exists to make one rule structurally impossible to break:
 * <strong>a file is never usable until something has looked at it.</strong>
 * A document only reaches {@link DocumentStatus#CLEAN} through
 * {@link #completeScan}, and only a CLEAN document satisfies a mandatory
 * requirement on an application. There is no path from "bytes arrived" to
 * "usable" that skips the scan, because the state machine has no such edge.
 *
 * <p>This aggregate holds metadata only. It never sees file content, and its
 * object key is built from opaque identifiers so that nothing identifying leaks
 * into S3 access logs, CloudTrail or a bucket inventory report.
 */
public final class Document {

    private final DocumentId id;
    private final String applicationId;
    private final DocumentType type;
    private final ObjectLocation location;
    private final String declaredContentType;
    private final Long declaredSizeBytes;
    private final Instant createdAt;
    private final Instant uploadExpiresAt;

    private DocumentStatus status;
    private StoredObject stored;
    private String scanReasonCode;
    private Instant updatedAt;
    private long version;

    private final List<DocumentDomainEvent> pendingEvents = new ArrayList<>();

    private Document(
            DocumentId id,
            String applicationId,
            DocumentType type,
            ObjectLocation location,
            String declaredContentType,
            Long declaredSizeBytes,
            DocumentStatus status,
            StoredObject stored,
            String scanReasonCode,
            Instant createdAt,
            Instant updatedAt,
            Instant uploadExpiresAt,
            long version) {
        this.id = id;
        this.applicationId = applicationId;
        this.type = type;
        this.location = location;
        this.declaredContentType = declaredContentType;
        this.declaredSizeBytes = declaredSizeBytes;
        this.status = status;
        this.stored = stored;
        this.scanReasonCode = scanReasonCode;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.uploadExpiresAt = uploadExpiresAt;
        this.version = version;
    }

    /** Reserves an upload slot. The presigned URL is issued by the adapter, never held here. */
    public static Document requestUpload(
            DocumentId id,
            String applicationId,
            DocumentType type,
            ObjectLocation location,
            String declaredContentType,
            Long declaredSizeBytes,
            Instant now,
            Instant uploadExpiresAt) {

        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(location, "location must not be null");
        UploadConstraints.validateContentType(declaredContentType);
        UploadConstraints.validateDeclaredSize(declaredSizeBytes);

        Document document = new Document(
                id,
                applicationId,
                type,
                location,
                declaredContentType,
                declaredSizeBytes,
                DocumentStatus.PENDING_UPLOAD,
                null,
                null,
                now,
                now,
                uploadExpiresAt,
                1L);
        document.pendingEvents.add(new UploadRequested(id, applicationId, type, location.objectKey(), 1L, now));
        return document;
    }

    /** Rebuilds from storage. Raises no events. */
    public static Document reconstitute(
            DocumentId id,
            String applicationId,
            DocumentType type,
            ObjectLocation location,
            String declaredContentType,
            Long declaredSizeBytes,
            DocumentStatus status,
            StoredObject stored,
            String scanReasonCode,
            Instant createdAt,
            Instant updatedAt,
            Instant uploadExpiresAt,
            long version) {
        return new Document(
                id,
                applicationId,
                type,
                location,
                declaredContentType,
                declaredSizeBytes,
                status,
                stored,
                scanReasonCode,
                createdAt,
                updatedAt,
                uploadExpiresAt,
                version);
    }

    /**
     * Records that the object actually arrived in S3, having verified it against
     * what the client said it would upload.
     *
     * <p>The verification is the point of this method. A presigned URL is a
     * bearer credential to write one key: the holder can put whatever bytes they
     * like there, of whatever size and type. Trusting the request that asked for
     * the URL, rather than checking what S3 actually reports, means the size
     * limit and the content-type restriction are decoration.
     *
     * @throws UploadVerificationFailedException when the stored object does not
     *         match what was declared
     */
    public void completeUpload(StoredObject storedObject, Instant now) {
        requireStatus(DocumentStatus.PENDING_UPLOAD);
        Objects.requireNonNull(storedObject, "storedObject must not be null");

        UploadConstraints.verifyStoredObject(storedObject, declaredContentType, declaredSizeBytes);

        this.stored = storedObject;
        this.status = DocumentStatus.UPLOADED;
        this.updatedAt = now;
        this.version++;
        pendingEvents.add(new UploadCompleted(id, applicationId, type, storedObject.sizeBytes(), version, now));
    }

    /** Marks the document as being scanned. */
    public void beginScan(Instant now) {
        requireStatus(DocumentStatus.UPLOADED);
        this.status = DocumentStatus.SCANNING;
        this.updatedAt = now;
        this.version++;
    }

    /**
     * Records the scan result.
     *
     * <p>The only path to CLEAN. Everything downstream — in particular the rule
     * that an application cannot be submitted without its mandatory documents —
     * depends on that being true.
     */
    public void completeScan(ScanOutcome outcome, String reasonCode, Instant now) {
        requireStatus(DocumentStatus.SCANNING);
        Objects.requireNonNull(outcome, "outcome must not be null");

        this.status = outcome == ScanOutcome.CLEAN ? DocumentStatus.CLEAN : DocumentStatus.REJECTED;
        this.scanReasonCode = reasonCode;
        this.updatedAt = now;
        this.version++;
        pendingEvents.add(new ScanCompleted(
                id, applicationId, type, outcome, reasonCode, stored == null ? 0L : stored.sizeBytes(), version, now));
    }

    /**
     * Rejects the document without scanning it.
     *
     * <p>Used when verification fails or an upload is abandoned. Deliberately
     * still a rejection rather than a deletion: an application that references a
     * document must be able to see why it is unusable.
     */
    public void reject(String reasonCode, Instant now) {
        if (status == DocumentStatus.CLEAN || status == DocumentStatus.REJECTED) {
            throw new IllegalDocumentStateException(id, status, DocumentStatus.REJECTED);
        }
        this.status = DocumentStatus.REJECTED;
        this.scanReasonCode = reasonCode;
        this.updatedAt = now;
        this.version++;
        pendingEvents.add(new ScanCompleted(
                id,
                applicationId,
                type,
                ScanOutcome.REJECTED,
                reasonCode,
                stored == null ? 0L : stored.sizeBytes(),
                version,
                now));
    }

    private void requireStatus(DocumentStatus required) {
        if (status != required) {
            throw new IllegalDocumentStateException(id, status, required);
        }
    }

    public List<DocumentDomainEvent> drainPendingEvents() {
        List<DocumentDomainEvent> drained = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return drained;
    }

    public List<DocumentDomainEvent> pendingEvents() {
        return List.copyOf(pendingEvents);
    }

    public DocumentId id() {
        return id;
    }

    public String applicationId() {
        return applicationId;
    }

    public DocumentType type() {
        return type;
    }

    public ObjectLocation location() {
        return location;
    }

    public String declaredContentType() {
        return declaredContentType;
    }

    public Long declaredSizeBytes() {
        return declaredSizeBytes;
    }

    public DocumentStatus status() {
        return status;
    }

    public StoredObject stored() {
        return stored;
    }

    public String scanReasonCode() {
        return scanReasonCode;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public Instant uploadExpiresAt() {
        return uploadExpiresAt;
    }

    public long version() {
        return version;
    }

    @Override
    public String toString() {
        return "Document[id=" + id + ", type=" + type + ", status=" + status + ", version=" + version + "]";
    }
}
