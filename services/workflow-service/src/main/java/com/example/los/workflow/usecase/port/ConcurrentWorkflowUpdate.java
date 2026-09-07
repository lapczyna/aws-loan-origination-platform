package com.example.los.workflow.usecase.port;

import com.example.los.workflow.domain.model.WorkflowId;

/**
 * Raised when another replica advanced the same workflow first.
 *
 * <p>Expected traffic, not an incident: several replicas poll for due work, and
 * the optimistic lock is what makes that safe. The scheduler logs it at debug and
 * moves on; the winner has already recorded the outcome.
 */
public class ConcurrentWorkflowUpdate extends RuntimeException {

    public static final String ERROR_CODE = "CONCURRENT_WORKFLOW_UPDATE";

    public ConcurrentWorkflowUpdate(WorkflowId id) {
        super("Workflow " + id + " was advanced concurrently by another replica");
    }
}
