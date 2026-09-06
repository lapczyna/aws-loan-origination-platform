package com.example.los.events.document;

/**
 * An upload slot was created and a presigned URL was issued to the client.
 *
 * <p>The presigned URL itself is never published, logged or persisted: it is a
 * bearer credential for writing to the documents bucket.
 *
 * @param documentId    opaque document identifier
 * @param applicationId opaque owning application identifier
 * @param documentType  requested document category, for example PROOF_OF_INCOME
 * @param objectKey     S3 object key; contains opaque identifiers only, never applicant details
 */
public record DocumentUploadRequestedV1(
        String documentId, String applicationId, String documentType, String objectKey)
        implements DocumentEventPayload {

    public static final int SCHEMA_VERSION = 1;
}
