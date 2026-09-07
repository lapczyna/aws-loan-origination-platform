package com.example.los.document.domain.event;

import java.time.Instant;

import com.example.los.document.domain.model.DocumentId;

/**
 * A fact recorded by a document.
 *
 * <p>Sealed so translation into published contracts is exhaustive.
 */
public sealed interface DocumentDomainEvent permits UploadRequested, UploadCompleted, ScanCompleted {

    DocumentId documentId();

    /** The owning application. Also the Kafka partition key. */
    String applicationId();

    long aggregateVersion();

    Instant occurredAt();
}
