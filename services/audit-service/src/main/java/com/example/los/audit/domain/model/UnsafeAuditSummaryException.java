package com.example.los.audit.domain.model;

/**
 * A summary contained a key that is not on the allow-list, or a value too long
 * to be a code.
 *
 * <p>Deliberately fatal to the write rather than something the service logs and
 * shrugs off. Dropping the offending key and carrying on would silently produce
 * an audit record missing the very fact somebody tried to record, and would hide
 * the mistake until an auditor asked why the trail was incomplete.
 */
public class UnsafeAuditSummaryException extends RuntimeException {

    public static final String ERROR_CODE = "UNSAFE_AUDIT_SUMMARY";

    public UnsafeAuditSummaryException(String message) {
        super(message);
    }

    public String errorCode() {
        return ERROR_CODE;
    }
}
