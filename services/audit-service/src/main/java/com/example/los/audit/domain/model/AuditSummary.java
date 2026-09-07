package com.example.los.audit.domain.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The allow-list governing what may be written into an audit summary.
 *
 * <p>This is the audit trail's privacy control, and it is an allow-list rather
 * than a deny-list on purpose. A deny-list is a promise to have thought of every
 * dangerous field name in advance, and it fails silently the first time somebody
 * adds one nobody anticipated. An allow-list fails loudly instead: a new field
 * is rejected until someone deliberately adds it here, which is exactly the
 * review step that ought to happen.
 *
 * <p>It matters more here than anywhere else on the platform. An audit store is
 * the longest-lived, most widely replicated and least frequently reviewed data
 * the system holds — exported to S3, retained for years under Object Lock,
 * readable by compliance tooling. Personal data written here is personal data
 * that no erasure request will ever fully remove.
 *
 * <p>Values are also length-bounded. An allowed key holding a megabyte of text is
 * still a problem: it bloats the chain, slows verification, and is exactly how
 * free-form content sneaks into a field that was only ever meant to hold a code.
 */
public final class AuditSummary {

    /** Long enough for any status, reason code or decision; far too short for prose. */
    public static final int MAXIMUM_VALUE_LENGTH = 128;

    /**
     * Every key an audit summary may contain.
     *
     * <p>All of them are codes, statuses or counts. None can hold a name, an
     * email address, an amount tied to an individual, or text a user typed.
     */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "status",
            "previousStatus",
            "newStatus",
            "decision",
            "recommendation",
            "reasonCode",
            "errorCode",
            "checkType",
            "outcome",
            "documentType",
            "scanOutcome",
            "attempt",
            "attempts",
            "decidedBy",
            "producer",
            "environment",
            "channel");

    private AuditSummary() {}

    /**
     * Validates a summary against the allow-list.
     *
     * @return the summary, with keys normalised
     * @throws UnsafeAuditSummaryException when a key is not allowed or a value is
     *         too long
     */
    public static Map<String, String> requireSafe(Map<String, String> summary) {
        Map<String, String> safe = new LinkedHashMap<>();

        summary.forEach((key, value) -> {
            if (key == null || !ALLOWED_KEYS.contains(key)) {
                // The key is named because it is a developer's own identifier and
                // is safe to report; the VALUE never is, and never appears.
                throw new UnsafeAuditSummaryException(
                        "Audit summary key '" + key + "' is not on the allow-list. Add it deliberately in "
                                + "AuditSummary after confirming it cannot carry personal data.");
            }
            if (value != null && value.length() > MAXIMUM_VALUE_LENGTH) {
                throw new UnsafeAuditSummaryException("Audit summary value for key '" + key + "' exceeds "
                        + MAXIMUM_VALUE_LENGTH + " characters. Audit values are codes, not prose.");
            }
            safe.put(key, value);
        });

        return Map.copyOf(safe);
    }

    /** True when the key may appear in an audit summary. */
    public static boolean isAllowed(String key) {
        return key != null && ALLOWED_KEYS.contains(key);
    }

    public static Set<String> allowedKeys() {
        return ALLOWED_KEYS;
    }

    /** Normalises an enum-like value for storage. Never applied to free text, which is not permitted at all. */
    public static String normalise(String value) {
        return value == null ? null : value.strip().toUpperCase(Locale.ROOT);
    }
}
