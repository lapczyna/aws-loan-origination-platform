package com.example.los.workflow.domain.model;

import java.util.Map;

import com.example.los.events.vocabulary.CheckOutcome;
import com.example.los.events.vocabulary.CheckType;
import com.example.los.events.vocabulary.Recommendation;

/**
 * Turns a complete set of check results into a recommendation.
 *
 * <p>The rules are ordered, and the order encodes the risk appetite of a lender
 * rather than a preference about code style:
 *
 * <ol>
 *   <li><b>A failed KYC or AML check rejects outright.</b> These are regulatory,
 *       not commercial. No score and no human discretion overrides them, so they
 *       are evaluated first and short-circuit everything else.
 *   <li><b>A failed fraud check rejects.</b>
 *   <li><b>Anything inconclusive goes to a human.</b> An inconclusive check means
 *       the external system could not decide. Treating that as a pass approves
 *       people nobody verified; treating it as a fail rejects people for a
 *       third party's data gap. A person looks at it.
 *   <li><b>A weak credit score with a high loan-to-income ratio goes to a
 *       human</b> rather than being rejected: this is the band where the
 *       applicant's circumstances legitimately change the answer.
 *   <li><b>Everything else is approved.</b>
 * </ol>
 *
 * <p>Pure and deterministic: same inputs, same recommendation, every time. That
 * is what makes an automated lending decision auditable and explainable, which is
 * a regulatory requirement and not merely a nice property.
 */
public final class AssessmentPolicy {

    /** Below this, the applicant is declined outright on credit grounds. */
    public static final int CREDIT_SCORE_REJECT_BELOW = 450;

    /** Between the reject threshold and this, a human decides. */
    public static final int CREDIT_SCORE_AUTO_APPROVE_FROM = 650;

    /** Above this ratio, a merely adequate score is not enough on its own. */
    public static final double LOAN_TO_INCOME_REVIEW_ABOVE = 3.0;

    /**
     * The outcome of evaluating the policy.
     *
     * @param recommendation what the application service should do
     * @param reasonCode     stable, safe code explaining why
     */
    public record Outcome(Recommendation recommendation, String reasonCode) {}

    public Outcome evaluate(Map<CheckType, CheckState> checks, AssessmentInputs inputs) {
        // Regulatory checks first. A short-circuit here is deliberate: no
        // commercial consideration may overturn a KYC or AML failure.
        for (CheckType regulatory : new CheckType[] {CheckType.KYC, CheckType.AML}) {
            if (outcomeOf(checks, regulatory) == CheckOutcome.FAILED) {
                return new Outcome(Recommendation.REJECT, WorkflowReasonCodes.REGULATORY_CHECK_FAILED);
            }
        }

        if (outcomeOf(checks, CheckType.FRAUD) == CheckOutcome.FAILED) {
            return new Outcome(Recommendation.REJECT, WorkflowReasonCodes.FRAUD_CHECK_FAILED);
        }

        // Any check that could not decide sends the case to a person. Guessing in
        // either direction is worse than asking.
        for (CheckType type : CheckType.values()) {
            if (outcomeOf(checks, type) == CheckOutcome.INCONCLUSIVE) {
                return new Outcome(Recommendation.MANUAL_REVIEW, WorkflowReasonCodes.CHECK_INCONCLUSIVE);
            }
        }

        if (outcomeOf(checks, CheckType.CREDIT_SCORE) == CheckOutcome.FAILED) {
            return new Outcome(Recommendation.REJECT, WorkflowReasonCodes.CREDIT_SCORE_BELOW_THRESHOLD);
        }

        Integer creditScore = scoreOf(checks, CheckType.CREDIT_SCORE);
        if (creditScore == null) {
            // A passed credit check with no score is a contract violation by the
            // bureau adapter. Reviewing it beats inventing a number.
            return new Outcome(Recommendation.MANUAL_REVIEW, WorkflowReasonCodes.CREDIT_SCORE_UNAVAILABLE);
        }

        if (creditScore < CREDIT_SCORE_REJECT_BELOW) {
            return new Outcome(Recommendation.REJECT, WorkflowReasonCodes.CREDIT_SCORE_BELOW_THRESHOLD);
        }

        if (creditScore < CREDIT_SCORE_AUTO_APPROVE_FROM) {
            return new Outcome(Recommendation.MANUAL_REVIEW, WorkflowReasonCodes.CREDIT_SCORE_IN_REVIEW_BAND);
        }

        if (inputs.loanToIncomeRatio() > LOAN_TO_INCOME_REVIEW_ABOVE) {
            return new Outcome(Recommendation.MANUAL_REVIEW, WorkflowReasonCodes.LOAN_TO_INCOME_REQUIRES_REVIEW);
        }

        return new Outcome(Recommendation.APPROVE, WorkflowReasonCodes.ALL_CHECKS_PASSED);
    }

    private static CheckOutcome outcomeOf(Map<CheckType, CheckState> checks, CheckType type) {
        CheckState state = checks.get(type);
        return state == null ? null : state.outcome();
    }

    private static Integer scoreOf(Map<CheckType, CheckState> checks, CheckType type) {
        CheckState state = checks.get(type);
        return state == null ? null : state.score();
    }
}
