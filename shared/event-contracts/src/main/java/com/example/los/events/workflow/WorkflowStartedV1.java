package com.example.los.events.workflow;

import java.util.List;

/**
 * Assessment of an application began.
 *
 * @param applicationId opaque application identifier
 * @param workflowId    opaque workflow instance identifier
 * @param checks        the checks scheduled for this application, in no particular order
 */
public record WorkflowStartedV1(String applicationId, String workflowId, List<String> checks)
        implements WorkflowEventPayload {

    public static final int SCHEMA_VERSION = 1;

    public WorkflowStartedV1 {
        checks = checks == null ? List.of() : List.copyOf(checks);
    }
}
