package com.example.los.application.observability;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

/**
 * The correlation identifier for the request being handled.
 *
 * <p>Held in the SLF4J MDC so every log line, every event and every error
 * response from one business interaction carries the same value, and an operator
 * investigating a customer complaint can follow it across four services.
 *
 * <p>A client may supply its own value through the {@code X-Correlation-Id}
 * header, which is what lets a caller correlate its own logs with the platform's.
 * Client input is never trusted verbatim: an unvalidated header ends up in log
 * files, in events and in audit records, so an attacker could inject newlines to
 * forge log entries, or paste a stolen credential into the header and have the
 * platform persist it for them. {@link #sanitise} therefore accepts only a short
 * conservative character set and replaces anything else with a fresh identifier.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Letters, digits, hyphens and underscores only, and short enough to bound log growth. */
    private static final Pattern ACCEPTABLE = Pattern.compile("^[A-Za-z0-9_-]{8,64}$");

    private CorrelationId() {}

    /**
     * Returns the supplied identifier if it is acceptable, otherwise a new one.
     *
     * <p>A rejected value is discarded silently rather than reported: telling a
     * caller their correlation identifier was refused invites probing, and the
     * request itself is perfectly valid.
     */
    public static String sanitise(String supplied) {
        if (supplied != null && ACCEPTABLE.matcher(supplied).matches()) {
            return supplied;
        }
        return UUID.randomUUID().toString();
    }

    public static void set(String correlationId) {
        MDC.put(MDC_KEY, correlationId);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    /** The current request's identifier, or {@code "unknown"} outside a request. */
    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null ? "unknown" : value;
    }
}
