package com.example.los.events;

/**
 * Kafka topic names.
 *
 * <p>Topics are versioned in the name. An incompatible change to a payload
 * schema produces a new topic rather than a breaking change on the existing one,
 * so producers and consumers can migrate independently.
 *
 * <p>Topics are created by controlled configuration (Docker Compose locally,
 * Terraform plus an administrative step on Amazon MSK). Automatic topic creation
 * is disabled outside local development.
 */
public final class Topics {

    /** Facts about the loan application aggregate. Partition key: applicationId. */
    public static final String APPLICATION_EVENTS = "los.application.events.v1";

    /** Facts about uploaded documents and their scan results. Partition key: applicationId. */
    public static final String DOCUMENT_EVENTS = "los.document.events.v1";

    /** Facts about workflow progress and outcomes. Partition key: applicationId. */
    public static final String WORKFLOW_EVENTS = "los.workflow.events.v1";

    /**
     * Events that could not be processed after the retry budget was exhausted.
     * Consumed by no service; drained deliberately through the replay runbook.
     */
    public static final String DEAD_LETTER = "los.dlq.v1";

    private Topics() {}
}
