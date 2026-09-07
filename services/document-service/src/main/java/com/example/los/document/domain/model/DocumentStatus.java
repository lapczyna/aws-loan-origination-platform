package com.example.los.document.domain.model;

/**
 * The quarantine lifecycle of an uploaded document.
 *
 * <p>The order matters: there is no transition from an unscanned state directly
 * to {@link #CLEAN}, which is what makes "nothing unscanned is ever usable" a
 * structural property rather than a convention someone has to remember.
 */
public enum DocumentStatus {

    /** Metadata exists and a presigned URL was issued. No bytes have arrived. */
    PENDING_UPLOAD,

    /** The object is in the quarantine area and has been verified against what was declared. */
    UPLOADED,

    /** A malware scan is in progress. */
    SCANNING,

    /** Scanned and accepted. The only status that satisfies a mandatory document requirement. */
    CLEAN,

    /** Refused: by the scanner, by verification, or because the upload was abandoned. Terminal. */
    REJECTED;

    public boolean isTerminal() {
        return this == CLEAN || this == REJECTED;
    }

    /** True while the object has arrived but has not yet been cleared for use. */
    public boolean isInQuarantine() {
        return this == UPLOADED || this == SCANNING;
    }
}
