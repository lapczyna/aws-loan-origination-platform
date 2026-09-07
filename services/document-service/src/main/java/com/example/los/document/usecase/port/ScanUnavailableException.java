package com.example.los.document.usecase.port;

/**
 * The scanner could not be reached.
 *
 * <p>Retryable, and emphatically not a rejection. Treating an unavailable scanner
 * as "not clean" would reject perfectly good documents during an outage and force
 * applicants to upload them again; treating it as "clean" would let unscanned
 * content through, which is the one thing this whole lifecycle exists to prevent.
 * So the document stays in SCANNING and the scan is retried.
 */
public class ScanUnavailableException extends RuntimeException {

    public static final String ERROR_CODE = "SCANNER_UNAVAILABLE";

    public ScanUnavailableException(String message) {
        super(message);
    }

    public String errorCode() {
        return ERROR_CODE;
    }
}
