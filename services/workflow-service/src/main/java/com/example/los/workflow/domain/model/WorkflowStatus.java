package com.example.los.workflow.domain.model;

/** Lifecycle of an assessment. */
public enum WorkflowStatus {

    /** Checks are scheduled, running or waiting to be retried. */
    RUNNING,

    /** Every check answered and a recommendation was produced. */
    COMPLETED,

    /**
     * A check exhausted its retry budget. Terminal and technical: nothing has
     * been decided about the applicant, which is why it is distinct from a
     * recommendation to reject.
     */
    FAILED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
