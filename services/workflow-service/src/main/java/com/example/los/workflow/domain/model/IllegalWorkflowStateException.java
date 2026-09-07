package com.example.los.workflow.domain.model;

/** Raised when an operation is attempted on a workflow that cannot accept it. */
public class IllegalWorkflowStateException extends RuntimeException {

    public static final String ERROR_CODE = "ILLEGAL_WORKFLOW_STATE";

    public IllegalWorkflowStateException(WorkflowId id, WorkflowStatus status) {
        super("Workflow " + id + " cannot accept this operation while it is " + status);
    }

    public String errorCode() {
        return ERROR_CODE;
    }
}
