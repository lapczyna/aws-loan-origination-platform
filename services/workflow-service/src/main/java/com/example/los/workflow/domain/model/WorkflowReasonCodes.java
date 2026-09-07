package com.example.los.workflow.domain.model;

/**
 * Stable reason codes published by the workflow context.
 *
 * <p>These appear in events, audit records, metric tags and eventually in
 * customer-facing explanations, so they are part of the public contract and must
 * not be renamed. A new situation gets a new code.
 */
public final class WorkflowReasonCodes {

    public static final String ALL_CHECKS_PASSED = "ALL_CHECKS_PASSED";
    public static final String REGULATORY_CHECK_FAILED = "REGULATORY_CHECK_FAILED";
    public static final String FRAUD_CHECK_FAILED = "FRAUD_CHECK_FAILED";
    public static final String CHECK_INCONCLUSIVE = "CHECK_INCONCLUSIVE";
    public static final String CREDIT_SCORE_BELOW_THRESHOLD = "CREDIT_SCORE_BELOW_THRESHOLD";
    public static final String CREDIT_SCORE_IN_REVIEW_BAND = "CREDIT_SCORE_IN_REVIEW_BAND";
    public static final String CREDIT_SCORE_UNAVAILABLE = "CREDIT_SCORE_UNAVAILABLE";
    public static final String LOAN_TO_INCOME_REQUIRES_REVIEW = "LOAN_TO_INCOME_REQUIRES_REVIEW";

    /** Technical: the check could not be completed within its retry budget. */
    public static final String CHECK_RETRY_BUDGET_EXHAUSTED = "CHECK_RETRY_BUDGET_EXHAUSTED";

    /** Technical: the external system did not answer in time. */
    public static final String CHECK_TIMED_OUT = "CHECK_TIMED_OUT";

    /** Technical: the external system is refusing traffic and the breaker is open. */
    public static final String CHECK_CIRCUIT_OPEN = "CHECK_CIRCUIT_OPEN";

    /** Technical: the external system returned an error it will not recover from. */
    public static final String CHECK_PERMANENT_ERROR = "CHECK_PERMANENT_ERROR";

    private WorkflowReasonCodes() {}
}
