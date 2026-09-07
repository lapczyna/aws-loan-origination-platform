package com.example.los.application.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.events.vocabulary.DocumentStatuses;

/**
 * Applies document facts to the local projection.
 *
 * <p>Every write goes through {@link DocumentStatusEntity#applyIfNewer}, so an
 * event that carries an older source version than the row already holds is
 * dropped. That is what keeps the projection correct when the broker delivers
 * events out of order, which it may legitimately do after a partition
 * reassignment or a producer retry.
 */
@Component
public class DocumentStatusProjector {

    private static final Logger log = LoggerFactory.getLogger(DocumentStatusProjector.class);

    private final DocumentStatusJpaRepository projection;

    DocumentStatusProjector(DocumentStatusJpaRepository projection) {
        this.projection = projection;
    }

    /** Records that an upload slot was created and is awaiting the file. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUploadRequested(
            UUID documentId, UUID applicationId, String documentType, long sourceVersion, Instant at) {
        upsert(documentId, applicationId, documentType, DocumentStatuses.PENDING_UPLOAD, sourceVersion, at);
    }

    /** Records the outcome of the malware scan: CLEAN or REJECTED. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordScanOutcome(
            UUID documentId,
            UUID applicationId,
            String documentType,
            String outcome,
            long sourceVersion,
            Instant at) {
        upsert(documentId, applicationId, documentType, outcome, sourceVersion, at);
    }

    private void upsert(
            UUID documentId,
            UUID applicationId,
            String documentType,
            String status,
            long sourceVersion,
            Instant at) {

        projection
                .findById(documentId)
                .ifPresentOrElse(
                        existing -> {
                            if (!existing.applyIfNewer(status, sourceVersion, at)) {
                                log.debug(
                                        "Stale document event ignored. documentId={} eventVersion={} rowVersion={}",
                                        documentId,
                                        sourceVersion,
                                        existing.getSourceVersion());
                            }
                        },
                        () -> projection.save(new DocumentStatusEntity(
                                documentId, applicationId, documentType, status, sourceVersion, at)));
    }
}
