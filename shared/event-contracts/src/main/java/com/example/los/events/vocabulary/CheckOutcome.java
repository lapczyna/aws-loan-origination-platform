package com.example.los.events.vocabulary;

/** Result of a single external check. */
public enum CheckOutcome {
    /** The check completed and the applicant satisfied it. */
    PASSED,
    /** The check completed and the applicant did not satisfy it. A business result, never retried. */
    FAILED,
    /** The check completed without a definitive answer. Routes to manual review. */
    INCONCLUSIVE
}
