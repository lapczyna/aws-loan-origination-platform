package com.example.los.events.document;

/** Marker for every payload published on the document events topic. */
public sealed interface DocumentEventPayload permits DocumentUploadRequestedV1, DocumentScanCompletedV1 {

    /** Opaque identifier of the document this fact concerns. */
    String documentId();

    /** Opaque identifier of the owning loan application; also the partition key. */
    String applicationId();
}
