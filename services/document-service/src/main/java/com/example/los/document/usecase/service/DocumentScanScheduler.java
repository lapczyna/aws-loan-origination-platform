package com.example.los.document.usecase.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.example.los.document.domain.model.DocumentId;

/**
 * Drives the scanner.
 *
 * <p>A separate bean from {@link ScanDocumentsUseCase} so that each document is
 * scanned in its own transaction through the Spring proxy. A self-invocation
 * would bypass the proxy entirely, and the scan result would then commit
 * separately from the event announcing it.
 */
@Component
public class DocumentScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(DocumentScanScheduler.class);

    private final ScanDocumentsUseCase scanning;

    DocumentScanScheduler(ScanDocumentsUseCase scanning) {
        this.scanning = scanning;
    }

    /**
     * Scans one batch of quarantined documents.
     *
     * @return how many produced a definitive result
     */
    @Scheduled(fixedDelayString = "${los.scanner.poll-interval:PT1S}")
    public int scanPendingDocuments() {
        List<DocumentId> claimed = scanning.claimBatch();
        int scanned = 0;

        for (DocumentId documentId : claimed) {
            try {
                if (scanning.scanOne(documentId, "scan-" + documentId)) {
                    scanned++;
                }
            } catch (RuntimeException e) {
                // Deliberately broad and deliberately per-document: one failure
                // must not abandon the rest of the batch, and the document stays
                // in SCANNING so it is retried rather than lost.
                log.error(
                        "Scanning a document failed; it stays in quarantine and will be retried. "
                                + "documentId={} type={}",
                        documentId,
                        e.getClass().getSimpleName(),
                        e);
            }
        }
        return scanned;
    }

    /** Refuses uploads whose presigned URL expired without an object arriving. */
    @Scheduled(fixedDelayString = "${los.scanner.abandoned-sweep-interval:PT5M}")
    public void sweepAbandonedUploads() {
        scanning.rejectAbandonedUploads("abandoned-upload-sweep");
    }
}
