package com.example.los.application.domain.model;

/**
 * The stable reason codes this bounded context emits.
 *
 * <p>These strings appear in events, audit records, metric tags and API
 * responses, so they are part of the platform's public contract and must not be
 * renamed. A new situation gets a new code rather than a redefinition of an
 * existing one.
 */
public final class ReasonCodes {

    public static final String SUBMISSION_ACCEPTED = "SUBMISSION_ACCEPTED";
    public static final String VALIDATION_PASSED = "VALIDATION_PASSED";
    public static final String ALL_CHECKS_PASSED = "ALL_CHECKS_PASSED";
    public static final String CHECK_FAILED = "CHECK_FAILED";
    public static final String CHECK_INCONCLUSIVE = "CHECK_INCONCLUSIVE";
    public static final String MANUAL_REVIEW_REQUIRED = "MANUAL_REVIEW_REQUIRED";
    public static final String REVIEWER_DECISION = "REVIEWER_DECISION";
    public static final String WITHDRAWN_BY_APPLICANT = "WITHDRAWN_BY_APPLICANT";
    public static final String ASSESSMENT_FAILED = "ASSESSMENT_FAILED";

    public static final String AMOUNT_BELOW_PRODUCT_MINIMUM = "AMOUNT_BELOW_PRODUCT_MINIMUM";
    public static final String AMOUNT_ABOVE_PRODUCT_MAXIMUM = "AMOUNT_ABOVE_PRODUCT_MAXIMUM";
    public static final String LOAN_TO_INCOME_TOO_HIGH = "LOAN_TO_INCOME_TOO_HIGH";
    public static final String UNSUPPORTED_RESIDENCE_COUNTRY = "UNSUPPORTED_RESIDENCE_COUNTRY";

    private ReasonCodes() {}
}
