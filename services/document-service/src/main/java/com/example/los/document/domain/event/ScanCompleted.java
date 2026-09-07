package com.example.los.document.domain.event;

import java.time.Instant;

import com.example.los.document.domain.model.DocumentId;
import com.example.los.events.vocabulary.DocumentType;
import com.example.los.events.vocabulary.ScanOutcome;

/**
 * The document was cleared or refused.
 *
 * <p>The event the application context waits for: only a CLEAN document
 * satisfies a mandatory requirement on a submission.
 */
public record ScanCompleted(
        DocumentId documentId,
        String applicationId,
        DocumentType documentType,
        ScanOutcome outcome,
        String reasonCode,
        long sizeBytes,
        long aggregateVersion,
        Instant occurredAt)
        implements DocumentDomainEvent {}
