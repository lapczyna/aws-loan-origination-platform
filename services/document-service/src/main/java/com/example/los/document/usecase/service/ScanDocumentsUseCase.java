package com.example.los.document.usecase.service;

import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.document.domain.model.Document;
import com.example.los.document.domain.model.DocumentId;
import com.example.los.document.domain.model.DocumentNotFoundException;
import com.example.los.document.usecase.port.DocumentRepository;
import com.example.los.document.usecase.port.MalwareScannerPort;
import com.example.los.document.usecase.port.ObjectStoragePort;
import com.example.los.document.usecase.port.OutboxWriter;
import com.example.los.document.usecase.port.ScanUnavailableException;
import com.example.los.events.vocabulary.ScanOutcome;

/**
 * Scans documents waiting in quarantine.
 *
 * <p>Runs as a background job, never on the request path. Scanning is slow and
 * depends on an external system, and a client's HTTP request should not be
 * waiting on either.
 *
 * <h2>What happens to a clean document</h2>
 *
 * <p>It is moved out of the quarantine prefix into the accepted prefix. The move
 * is what lets a bucket policy and an IAM policy express "nothing may read under
 * {@code quarantine/} except the scanner"; a status column alone cannot be
 * enforced by the storage layer, so an unscanned object would remain readable by
 * anything that could reach the bucket.
 *
 * <h2>What happens when the scanner is down</h2>
 *
 * <p>Nothing. The document stays in SCANNING and is picked up again later.
 * Treating an unreachable scanner as a rejection would refuse good documents
 * during an outage and make applicants upload them again; treating it as a pass
 * would let unscanned content through, which is the single thing this lifecycle
 * exists to prevent.
 */
@Service
public class ScanDocumentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ScanDocumentsUseCase.class);

    private final DocumentRepository documents;
    private final MalwareScannerPort scanner;
    private final ObjectStoragePort storage;
    private final OutboxWriter outbox;
    private final Clock clock;
    private final int batchSize;

    public ScanDocumentsUseCase(
            DocumentRepository documents,
            MalwareScannerPort scanner,
            ObjectStoragePort storage,
            OutboxWriter outbox,
            Clock clock,
            @Value("${los.scanner.batch-size:20}") int batchSize) {
        this.documents = documents;
        this.scanner = scanner;
        this.storage = storage;
        this.outbox = outbox;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /** Identifiers of documents claimed for scanning, in their own transaction. */
    @Transactional
    public List<DocumentId> claimBatch() {
        return documents.claimAwaitingScan(batchSize).stream().map(Document::id).toList();
    }

    /**
     * Scans one document.
     *
     * <p>Its own transaction, called from {@link DocumentScanScheduler} so the
     * proxy applies. One document failing must not roll back the results already
     * recorded for the others in the batch.
     *
     * @return true when a definitive result was recorded
     */
    @Transactional
    public boolean scanOne(DocumentId documentId, String correlationId) {
        Document document = documents.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));

        MalwareScannerPort.ScanResult result;
        try {
            result = scanner.scan(document.location());
        } catch (ScanUnavailableException e) {
            // Left in SCANNING deliberately. Neither "clean" nor "rejected" is a
            // safe guess when the scanner did not answer.
            log.warn(
                    "Scanner unavailable; the document stays in quarantine and will be retried. "
                            + "documentId={} errorCode={}",
                    documentId,
                    e.errorCode());
            return false;
        }

        document.completeScan(result.outcome(), result.reasonCode(), clock.instant());

        if (result.outcome() == ScanOutcome.CLEAN) {
            // Only a cleared object leaves quarantine.
            storage.moveToAccepted(document.location(), document.location().acceptedCounterpart());
        }

        documents.save(document);
        outbox.append(document.drainPendingEvents(), correlationId);

        log.info(
                "Scan completed. documentId={} outcome={} reasonCode={} correlationId={}",
                documentId,
                result.outcome(),
                result.reasonCode(),
                correlationId);

        return true;
    }

    /**
     * Refuses uploads whose presigned URL expired without an object arriving.
     *
     * <p>Without this sweep an application waits forever on a document its owner
     * abandoned, and the metadata row outlives any purpose it had.
     */
    @Transactional
    public int rejectAbandonedUploads(String correlationId) {
        List<Document> abandoned = documents.findAbandonedUploads(clock.instant(), batchSize);

        for (Document document : abandoned) {
            document.reject("UPLOAD_ABANDONED", clock.instant());
            documents.save(document);
            outbox.append(document.drainPendingEvents(), correlationId);
        }

        if (!abandoned.isEmpty()) {
            log.info("Refused {} abandoned upload(s) whose presigned URL had expired", abandoned.size());
        }
        return abandoned.size();
    }
}
