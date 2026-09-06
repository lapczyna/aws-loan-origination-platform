package com.example.los.events.vocabulary;

/**
 * The document quarantine lifecycle, as published on the document events topic.
 *
 * <p>Constants rather than an enum because these values appear in projections
 * maintained by other bounded contexts, which must be able to store a status a
 * newer document service introduced without failing to deserialise it. A
 * consumer compares against these constants and treats anything else as
 * "not yet clean", which is always the safe interpretation.
 *
 * <pre>
 *   PENDING_UPLOAD ──► UPLOADED ──► SCANNING ──► CLEAN
 *                                            └─► REJECTED
 * </pre>
 */
public final class DocumentStatuses {

    /** Metadata exists and a presigned URL was issued; no bytes have arrived. */
    public static final String PENDING_UPLOAD = "PENDING_UPLOAD";

    /** The object is in the quarantine area and has not been scanned. */
    public static final String UPLOADED = "UPLOADED";

    /** A scan is in progress. */
    public static final String SCANNING = "SCANNING";

    /** Scanned and accepted. The only status that satisfies a mandatory document. */
    public static final String CLEAN = "CLEAN";

    /** Scanned and refused, or failed validation. Terminal. */
    public static final String REJECTED = "REJECTED";

    private DocumentStatuses() {}
}
