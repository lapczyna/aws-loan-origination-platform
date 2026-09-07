package com.example.los.audit.usecase.port;

/**
 * This source event has already been recorded in this chain.
 *
 * <p>Expected traffic on an at-least-once bus, not an incident. Appending a
 * second record for the same event would extend the chain with a duplicate that
 * an auditor could not distinguish from a genuine repeated action.
 */
public class DuplicateAuditRecordException extends RuntimeException {

    public static final String ERROR_CODE = "DUPLICATE_AUDIT_RECORD";

    public DuplicateAuditRecordException(String chainKey, String sourceEventId) {
        super("Event " + sourceEventId + " is already recorded in chain " + chainKey);
    }
}
