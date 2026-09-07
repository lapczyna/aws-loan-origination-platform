package com.example.los.document.usecase.service;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.document.domain.model.Document;
import com.example.los.document.domain.model.DocumentId;
import com.example.los.document.domain.model.DocumentNotFoundException;
import com.example.los.document.domain.model.ObjectLocation;
import com.example.los.document.domain.model.StoredObject;
import com.example.los.document.domain.model.UploadConstraints;
import com.example.los.document.domain.model.UploadVerificationFailedException;
import com.example.los.document.usecase.port.DocumentRepository;
import com.example.los.document.usecase.port.ObjectStoragePort;
import com.example.los.document.usecase.port.OutboxWriter;
import com.example.los.events.vocabulary.DocumentType;

/**
 * Requesting an upload and completing one.
 *
 * <h2>Why the URL is issued outside the transaction's result</h2>
 *
 * <p>The metadata row is written first and the presigned URL is generated after.
 * If it were the other way round, a rollback would leave a live write credential
 * for a key the platform has no record of — an object nobody would ever scan,
 * charge storage for, or be able to explain.
 *
 * <p>The URL is returned to the caller and then forgotten. It is not stored, not
 * logged and not published, because it is a bearer credential for writing to the
 * documents bucket.
 *
 * <h2>Why completion re-reads S3</h2>
 *
 * <p>Completion does not trust the client's claim that the upload happened, or
 * its claim about what it uploaded. It asks S3 what is actually there and
 * verifies that against what was declared. A presigned URL permits arbitrary
 * bytes at one key; without this check the size limit and the content-type
 * allow-list would be advisory.
 */
@Service
public class DocumentUploadUseCase {

    private static final Logger log = LoggerFactory.getLogger(DocumentUploadUseCase.class);

    private final DocumentRepository documents;
    private final ObjectStoragePort storage;
    private final OutboxWriter outbox;
    private final DocumentRejectionRecorder rejections;
    private final Clock clock;
    private final String bucket;
    private final Duration urlValidity;

    public DocumentUploadUseCase(
            DocumentRepository documents,
            ObjectStoragePort storage,
            OutboxWriter outbox,
            DocumentRejectionRecorder rejections,
            Clock clock,
            @Value("${los.documents.bucket}") String bucket,
            @Value("${los.documents.presigned-url-validity:PT10M}") Duration urlValidity) {
        this.documents = documents;
        this.storage = storage;
        this.outbox = outbox;
        this.rejections = rejections;
        this.clock = clock;
        this.bucket = bucket;
        this.urlValidity = urlValidity;
    }

    /**
     * The result of requesting an upload.
     *
     * <p>Holds the credential only long enough to return it to the caller.
     *
     * @param documentId the document the client should later mark complete
     * @param upload     the presigned URL and the headers the client must send
     */
    public record UploadTicket(DocumentId documentId, ObjectStoragePort.PresignedUpload upload) {

        /** Redacted: contains a write credential. */
        @Override
        public String toString() {
            return "UploadTicket[documentId=" + documentId + ", upload=redacted]";
        }
    }

    /**
     * Reserves an upload slot and issues a short-lived presigned URL.
     *
     * @param declaredSizeBytes optional; when supplied it is checked against what
     *                          S3 reports on completion
     */
    @Transactional
    public UploadTicket requestUpload(
            String applicationId,
            DocumentType documentType,
            String declaredContentType,
            Long declaredSizeBytes,
            String correlationId) {

        DocumentId documentId = DocumentId.newId();
        // The key is built from identifiers only. Nothing derived from a filename
        // or an applicant attribute reaches it; see ObjectLocation.
        ObjectLocation location = ObjectLocation.quarantine(bucket, applicationId, documentId);

        Document document = Document.requestUpload(
                documentId,
                applicationId,
                documentType,
                location,
                declaredContentType,
                declaredSizeBytes,
                clock.instant(),
                clock.instant().plus(urlValidity));

        documents.save(document);
        outbox.append(document.drainPendingEvents(), correlationId);

        ObjectStoragePort.PresignedUpload upload =
                storage.presignUpload(location, declaredContentType, urlValidity);

        // Identifiers and the validity. Never the URL.
        log.info(
                "Upload requested. applicationId={} documentId={} type={} validitySeconds={} correlationId={}",
                applicationId,
                documentId,
                documentType,
                urlValidity.toSeconds(),
                correlationId);

        return new UploadTicket(documentId, upload);
    }

    /**
     * Confirms that the object arrived, verifies it, and queues it for scanning.
     *
     * @param expectedChecksumSha256 optional; when supplied it is compared with
     *                               the checksum S3 computed, giving end-to-end
     *                               integrity rather than the client's word
     */
    @Transactional
    public Document completeUpload(DocumentId documentId, String expectedChecksumSha256, String correlationId) {
        Document document = documents.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));

        Optional<StoredObject> stored = storage.describe(document.location());
        if (stored.isEmpty()) {
            // The client says it uploaded; S3 says there is nothing there. The
            // document is refused rather than left pending, so an application is
            // not blocked indefinitely on a file that never arrived.
            //
            // Recorded through DocumentRejectionRecorder, which commits in its
            // own transaction. Marking it here and then throwing would roll the
            // rejection back with the exception that reports it, leaving the
            // document silently PENDING_UPLOAD.
            rejections.recordRejection(documentId, "UPLOAD_NOT_FOUND", correlationId);
            throw new UploadVerificationFailedException(
                    "UPLOAD_NOT_FOUND", "No uploaded object was found for this document");
        }

        StoredObject storedObject = stored.get();
        try {
            UploadConstraints.verifyChecksum(storedObject, expectedChecksumSha256);
            // Also verifies size and content type against what was declared.
            document.completeUpload(storedObject, clock.instant());
        } catch (UploadVerificationFailedException e) {
            // Verification failure is a security event, not a validation slip:
            // the holder of a write credential uploaded something other than what
            // they asked permission for. The object is refused and left in
            // quarantine for investigation rather than promoted or deleted.
            //
            // Again committed independently, so the refusal survives the throw.
            rejections.recordRejection(documentId, e.errorCode(), correlationId);
            throw e;
        }

        // Queued rather than scanned inline: scanning is slow and unreliable, and
        // a client waiting on an HTTP response should not be waiting on it.
        document.beginScan(clock.instant());

        documents.save(document);
        outbox.append(document.drainPendingEvents(), correlationId);

        log.info(
                "Upload completed and queued for scanning. documentId={} sizeBytes={} correlationId={}",
                documentId,
                storedObject.sizeBytes(),
                correlationId);

        return document;
    }

    @Transactional(readOnly = true)
    public List<Document> listForApplication(String applicationId) {
        return documents.findByApplicationId(applicationId);
    }

    @Transactional(readOnly = true)
    public Document getById(DocumentId documentId) {
        return documents.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));
    }
}
