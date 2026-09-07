package com.example.los.document.domain.event;

import java.time.Instant;

import com.example.los.document.domain.model.DocumentId;
import com.example.los.events.vocabulary.DocumentType;

/**
 * An upload slot was reserved and a presigned URL was issued.
 *
 * <p>The URL itself is deliberately absent. It is a bearer credential for writing
 * to the documents bucket, and an event is persisted, replicated across brokers,
 * consumed by several services and copied into an audit store. A credential in
 * an event is a credential in all of those places.
 */
public record UploadRequested(
        DocumentId documentId,
        String applicationId,
        DocumentType documentType,
        String objectKey,
        long aggregateVersion,
        Instant occurredAt)
        implements DocumentDomainEvent {}
