package com.example.los.document.usecase.service;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.document.domain.model.Document;
import com.example.los.document.domain.model.DocumentId;
import com.example.los.document.domain.model.DocumentNotFoundException;
import com.example.los.document.usecase.port.DocumentRepository;
import com.example.los.document.usecase.port.OutboxWriter;

/**
 * Records a document rejection in its own transaction.
 *
 * <p>This exists because of a subtle and genuinely dangerous interaction. When an
 * upload fails verification, two things have to happen: the document must be
 * marked REJECTED, and the caller must be told the upload was refused. The
 * natural implementation — mark it, then throw — does not work: the exception
 * rolls back the very transaction that recorded the rejection, so the caller sees
 * an error while the document quietly stays PENDING_UPLOAD.
 *
 * <p>The consequence is not cosmetic. A document stuck in PENDING_UPLOAD keeps
 * its presigned URL alive until it expires, holds an application open waiting for
 * a file that was already refused, and leaves no record anywhere that anything
 * was rejected. The failure would be invisible until someone asked why an
 * application never progressed.
 *
 * <p>{@link Propagation#REQUIRES_NEW} suspends the caller's transaction and
 * commits the rejection independently, so it survives the exception that reports
 * it.
 */
@Component
public class DocumentRejectionRecorder {

    private static final Logger log = LoggerFactory.getLogger(DocumentRejectionRecorder.class);

    private final DocumentRepository documents;
    private final OutboxWriter outbox;
    private final Clock clock;

    DocumentRejectionRecorder(DocumentRepository documents, OutboxWriter outbox, Clock clock) {
        this.documents = documents;
        this.outbox = outbox;
        this.clock = clock;
    }

    /**
     * Marks the document rejected and commits, independently of the caller.
     *
     * @param reasonCode stable, safe code explaining the refusal
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRejection(DocumentId documentId, String reasonCode, String correlationId) {
        Document document = documents.findById(documentId).orElseThrow(() -> new DocumentNotFoundException(documentId));

        document.reject(reasonCode, clock.instant());
        documents.save(document);
        outbox.append(document.drainPendingEvents(), correlationId);

        log.warn(
                "Document rejected. documentId={} reasonCode={} correlationId={}",
                documentId,
                reasonCode,
                correlationId);
    }
}
