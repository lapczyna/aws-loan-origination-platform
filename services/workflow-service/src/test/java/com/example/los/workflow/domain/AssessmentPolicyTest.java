package com.example.los.workflow.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import com.example.los.events.vocabulary.CheckOutcome;
import com.example.los.events.vocabulary.CheckType;
import com.example.los.events.vocabulary.Recommendation;
import com.example.los.workflow.domain.model.AssessmentInputs;
import com.example.los.workflow.domain.model.AssessmentPolicy;
import com.example.los.workflow.domain.model.CheckState;
import com.example.los.workflow.domain.model.WorkflowReasonCodes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The decision rules.
 *
 * <p>These tests are the specification of how the platform lends money. Every
 * case here is a deliberate policy choice, not an implementation detail, so each
 * one states the reasoning as well as the expectation.
 */
class AssessmentPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-15T09:30:00Z");

    private final AssessmentPolicy policy = new AssessmentPolicy();

    /** Comfortably affordable: a low loan-to-income ratio keeps that rule out of the way. */
    private static AssessmentInputs affordableRequest() {
        return new AssessmentInputs(1_000_000L, "EUR", 48, "HOME_IMPROVEMENT", 5_000_000L, "PL-STD-01");
    }

    /** Ratio above the review threshold, everything else equal. */
    private static AssessmentInputs stretchedRequest() {
        return new AssessmentInputs(4_500_000L, "EUR", 48, "HOME_IMPROVEMENT", 1_000_000L, "PL-STD-01");
    }

    @Nested
    @DisplayName("regulatory checks")
    class RegulatoryChecks {

        @ParameterizedTest
        @EnumSource(
                value = CheckType.class,
                names = {"KYC", "AML"})
        @DisplayName("a failure rejects outright, whatever the credit score says")
        void regulatoryFailureRejectsRegardlessOfCreditScore(CheckType regulatoryCheck) {
            // An excellent credit score must not buy its way past a sanctions
            // match or a failed identity check. These are legal obligations, not
            // commercial trade-offs, which is why they are evaluated first.
            Map<CheckType, CheckState> checks = allPassing(900);
            checks.put(regulatoryCheck, failed(regulatoryCheck));

            AssessmentPolicy.Outcome outcome = policy.evaluate(checks, affordableRequest());

            assertThat(outcome.recommendation()).isEqualTo(Recommendation.REJECT);
            assertThat(outcome.reasonCode()).isEqualTo(WorkflowReasonCodes.REGULATORY_CHECK_FAILED);
        }

        @Test
        @DisplayName("a regulatory failure is never routed to a human to overrule")
        void regulatoryFailureIsNotSentToManualReview() {
            Map<CheckType, CheckState> checks = allPassing(900);
            checks.put(CheckType.AML, failed(CheckType.AML));
            // Even with another check inconclusive, which would normally route to
            // review, the regulatory failure decides.
            checks.put(CheckType.FRAUD, inconclusive(CheckType.FRAUD));

            assertThat(policy.evaluate(checks, affordableRequest()).recommendation())
                    .isEqualTo(Recommendation.REJECT);
        }
    }

    @Test
    @DisplayName("a failed fraud check rejects")
    void fraudFailureRejects() {
        Map<CheckType, CheckState> checks = allPassing(900);
        checks.put(CheckType.FRAUD, failed(CheckType.FRAUD));

        AssessmentPolicy.Outcome outcome = policy.evaluate(checks, affordableRequest());

        assertThat(outcome.recommendation()).isEqualTo(Recommendation.REJECT);
        assertThat(outcome.reasonCode()).isEqualTo(WorkflowReasonCodes.FRAUD_CHECK_FAILED);
    }

    @ParameterizedTest
    @EnumSource(CheckType.class)
    @DisplayName("any inconclusive check sends the application to a human")
    void inconclusiveCheckGoesToManualReview(CheckType inconclusiveCheck) {
        // An inconclusive result means the provider could not decide. Treating it
        // as a pass approves someone nobody verified; treating it as a failure
        // penalises an applicant for a third party's data gap.
        Map<CheckType, CheckState> checks = allPassing(900);
        checks.put(inconclusiveCheck, inconclusive(inconclusiveCheck));

        AssessmentPolicy.Outcome outcome = policy.evaluate(checks, affordableRequest());

        assertThat(outcome.recommendation()).isEqualTo(Recommendation.MANUAL_REVIEW);
        assertThat(outcome.reasonCode()).isEqualTo(WorkflowReasonCodes.CHECK_INCONCLUSIVE);
    }

    @ParameterizedTest(name = "score {0} -> {1}")
    @CsvSource({
        "300, REJECT",
        "449, REJECT",
        "450, MANUAL_REVIEW",
        "649, MANUAL_REVIEW",
        "650, APPROVE",
        "900, APPROVE",
    })
    @DisplayName("the credit score bands are exactly as specified at their boundaries")
    void creditScoreBands(int score, Recommendation expected) {
        // Boundaries are tested explicitly because an off-by-one here is the
        // difference between declining and approving a real applicant.
        assertThat(policy.evaluate(allPassing(score), affordableRequest()).recommendation())
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("a good score still goes to review when the loan is large relative to income")
    void highLoanToIncomeRequiresReviewEvenWithAGoodScore() {
        // The applicant is creditworthy in general but the specific request is
        // stretching. That is a judgement about circumstances, which is exactly
        // what a human reviewer is for.
        AssessmentPolicy.Outcome outcome = policy.evaluate(allPassing(900), stretchedRequest());

        assertThat(outcome.recommendation()).isEqualTo(Recommendation.MANUAL_REVIEW);
        assertThat(outcome.reasonCode()).isEqualTo(WorkflowReasonCodes.LOAN_TO_INCOME_REQUIRES_REVIEW);
    }

    @Test
    @DisplayName("a passed credit check with no score goes to review, never to approval")
    void missingCreditScoreIsReviewedRatherThanAssumed() {
        // A passed credit check with no score is a contract violation by the
        // adapter. Reviewing it beats inventing a number and lending on it.
        Map<CheckType, CheckState> checks = allPassing(900);
        checks.put(
                CheckType.CREDIT_SCORE,
                new CheckState(
                        CheckType.CREDIT_SCORE, CheckOutcome.PASSED, "SCORE_RETRIEVED", null, 1, null, null, NOW));

        AssessmentPolicy.Outcome outcome = policy.evaluate(checks, affordableRequest());

        assertThat(outcome.recommendation()).isEqualTo(Recommendation.MANUAL_REVIEW);
        assertThat(outcome.reasonCode()).isEqualTo(WorkflowReasonCodes.CREDIT_SCORE_UNAVAILABLE);
    }

    @Test
    @DisplayName("everything passing with an affordable request approves")
    void cleanApplicationIsApproved() {
        AssessmentPolicy.Outcome outcome = policy.evaluate(allPassing(800), affordableRequest());

        assertThat(outcome.recommendation()).isEqualTo(Recommendation.APPROVE);
        assertThat(outcome.reasonCode()).isEqualTo(WorkflowReasonCodes.ALL_CHECKS_PASSED);
    }

    @Test
    @DisplayName("the policy is deterministic, which is what makes a decision explainable")
    void policyIsDeterministic() {
        // An automated lending decision has to be reproducible and defensible.
        // Evaluating the same evidence twice must give the same answer.
        Map<CheckType, CheckState> checks = allPassing(700);
        AssessmentInputs inputs = affordableRequest();

        for (int i = 0; i < 50; i++) {
            assertThat(policy.evaluate(checks, inputs)).isEqualTo(policy.evaluate(checks, inputs));
        }
    }

    // --- fixtures ------------------------------------------------------------

    private static Map<CheckType, CheckState> allPassing(int creditScore) {
        Map<CheckType, CheckState> checks = new EnumMap<>(CheckType.class);
        checks.put(CheckType.KYC, passed(CheckType.KYC, null));
        checks.put(CheckType.AML, passed(CheckType.AML, null));
        checks.put(CheckType.FRAUD, passed(CheckType.FRAUD, 100));
        checks.put(CheckType.CREDIT_SCORE, passed(CheckType.CREDIT_SCORE, creditScore));
        return checks;
    }

    private static CheckState passed(CheckType type, Integer score) {
        return new CheckState(type, CheckOutcome.PASSED, "OK", score, 1, null, null, NOW);
    }

    private static CheckState failed(CheckType type) {
        return new CheckState(type, CheckOutcome.FAILED, "REFUSED", null, 1, null, null, NOW);
    }

    private static CheckState inconclusive(CheckType type) {
        return new CheckState(type, CheckOutcome.INCONCLUSIVE, "UNKNOWN", null, 1, null, null, NOW);
    }
}
