package com.example.los.events;

/**
 * Stable logical event type names.
 *
 * <p>These strings are part of the public contract: consumers route on them and
 * audit records store them. They must never be renamed. A change in meaning
 * requires a new name and a new schema version.
 */
public final class EventTypes {

    public static final String APPLICATION_SUBMITTED = "application.submitted";
    public static final String APPLICATION_STATUS_CHANGED = "application.status-changed";
    public static final String APPLICATION_DECISION_RECORDED = "application.decision-recorded";

    public static final String DOCUMENT_UPLOAD_REQUESTED = "document.upload-requested";
    public static final String DOCUMENT_SCAN_COMPLETED = "document.scan-completed";

    public static final String WORKFLOW_STARTED = "workflow.started";
    public static final String WORKFLOW_CHECK_COMPLETED = "workflow.check-completed";
    public static final String WORKFLOW_COMPLETED = "workflow.completed";
    public static final String WORKFLOW_FAILED = "workflow.failed";

    private EventTypes() {}
}
