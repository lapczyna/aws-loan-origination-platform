package com.example.los.events.document;

/**
 * A malware scan finished for an uploaded document.
 *
 * <p>The scanner behind this event is SIMULATED in this reference
 * implementation. See {@code docs/adr/ADR-0008} for how Amazon GuardDuty Malware
 * Protection for S3 replaces it.
 *
 * @param documentId    opaque document identifier
 * @param applicationId opaque owning application identifier
 * @param documentType  document category
 * @param outcome       CLEAN or REJECTED
 * @param reasonCode    stable, safe reason code when the document was rejected
 * @param sizeBytes     size of the stored object, used for reporting and quota checks
 */
public record DocumentScanCompletedV1(
        String documentId,
        String applicationId,
        String documentType,
        String outcome,
        String reasonCode,
        long sizeBytes)
        implements DocumentEventPayload {

    public static final int SCHEMA_VERSION = 1;
}
