package com.example.los.document.domain.event;

import java.time.Instant;

import com.example.los.document.domain.model.DocumentId;
import com.example.los.events.vocabulary.DocumentType;

/**
 * The object arrived in S3 and matched what the client declared.
 *
 * <p>Internal to this context: nothing outside it can act on a document that has
 * not been scanned yet, and publishing this would invite a consumer to try.
 */
public record UploadCompleted(
        DocumentId documentId,
        String applicationId,
        DocumentType documentType,
        long sizeBytes,
        long aggregateVersion,
        Instant occurredAt)
        implements DocumentDomainEvent {}
