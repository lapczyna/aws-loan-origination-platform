package com.example.los.events;

import java.util.Set;

/**
 * The complete set of metadata keys permitted on an {@link EventEnvelope}.
 *
 * <p>The allow-list exists so that adding metadata is a deliberate, reviewable
 * act. Anything not listed here is rejected, which prevents personal data from
 * being added to the event stream by accident.
 */
public final class EventMetadataKeys {

    /** Logical name of the service that produced the event. */
    public static final String PRODUCER = "producer";

    /** Deployment environment name, for example {@code local}. */
    public static final String ENVIRONMENT = "environment";

    /** Distributed trace identifier, for correlating events with traces. */
    public static final String TRACE_ID = "traceId";

    /** Channel through which the originating request arrived, for example {@code partner-api}. */
    public static final String CHANNEL = "channel";

    private static final Set<String> ALLOWED = Set.of(PRODUCER, ENVIRONMENT, TRACE_ID, CHANNEL);

    private EventMetadataKeys() {}

    public static boolean isAllowed(String key) {
        return ALLOWED.contains(key);
    }

    public static Set<String> allowed() {
        return ALLOWED;
    }
}
