package com.example.los.workflow.domain;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.example.los.events.vocabulary.CheckOutcome;
import com.example.los.events.vocabulary.CheckType;
import com.example.los.events.vocabulary.Recommendation;
import com.example.los.workflow.domain.event.CheckCompleted;
import com.example.los.workflow.domain.event.WorkflowCompleted;
import com.example.los.workflow.domain.event.WorkflowFailed;
import com.example.los.workflow.domain.event.WorkflowStarted;
import com.example.los.workflow.domain.model.AssessmentInputs;
import com.example.los.workflow.domain.model.AssessmentPolicy;
import com.example.los.workflow.domain.model.CheckState;
import com.example.los.workflow.domain.model.IllegalWorkflowStateException;
import com.example.los.workflow.domain.model.WorkflowId;
import com.example.los.workflow.domain.model.WorkflowInstance;
import com.example.los.workflow.domain.model.WorkflowReasonCodes;
import com.example.los.workflow.domain.model.WorkflowStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Behaviour of the durable assessment state machine. */
class WorkflowInstanceTest {

    private static final Instant NOW = Instant.parse("2026-01-15T09:30:00Z");
    private static final String APPLICATION_ID = "0199a1f0-0000-7000-8000-000000000001";
    private static final String APPLICANT_REFERENCE = "APR-7f3c9a21d4e85b06";

    private final AssessmentPolicy policy = new AssessmentPolicy();

    private static AssessmentInputs inputs() {
        return new AssessmentInputs(1_000_000L, "EUR", 48, "HOME_IMPROVEMENT", 5_000_000L, "PL-STD-01");
    }

    private static WorkflowInstance started() {
        return WorkflowInstance.start(WorkflowId.newId(), APPLICATION_ID, APPLICANT_REFERENCE, inputs(), NOW);
    }

    @Test
    @DisplayName("starting schedules every check immediately, so they run in parallel")
    void startSchedulesAllChecksAtOnce() {
        WorkflowInstance instance = started();

        assertThat(instance.status()).isEqualTo(WorkflowStatus.RUNNING);
        assertThat(instance.checks()).hasSize(CheckType.values().length);
        // All due now. Sequencing independent checks would multiply the slowest
        // provider's latency by four for no benefit.
        assertThat(instance.checksDueAt(NOW)).hasSize(CheckType.values().length);
        assertThat(instance.pendingEvents()).singleElement().isInstanceOf(WorkflowStarted.class);
    }

    @Test
    @DisplayName("a completed check is no longer due and is never run again")
    void completedCheckIsNotRescheduled() {
        WorkflowInstance instance = started();

        instance.recordCheckOutcome(CheckType.KYC, CheckOutcome.PASSED, "IDENTITY_VERIFIED", null, NOW);

        assertThat(instance.checksDueAt(NOW)).noneMatch(check -> check.type() == CheckType.KYC);
        assertThat(instance.checks().get(CheckType.KYC).isComplete()).isTrue();
        assertThat(instance.pendingEvents()).hasAtLeastOneElementOfType(CheckCompleted.class);
    }

    @Test
    @DisplayName("a business rejection is terminal for that check and is never retried")
    void businessRejectionIsNotRetried() {
        // The provider answered. Retrying could only produce a different result
        // by accident, and would delay telling the applicant.
        WorkflowInstance instance = started();

        instance.recordCheckOutcome(CheckType.AML, CheckOutcome.FAILED, "SANCTIONS_LIST_MATCH", null, NOW);

        assertThat(instance.checks().get(CheckType.AML).isComplete()).isTrue();
        assertThat(instance.checksDueAt(NOW.plusSeconds(3600)))
                .noneMatch(check -> check.type() == CheckType.AML);
    }

    @Test
    @DisplayName("a transient failure reschedules the check into the future")
    void transientFailureSchedulesARetry() {
        WorkflowInstance instance = started();

        instance.recordTransientFailure(
                CheckType.CREDIT_SCORE, WorkflowReasonCodes.CHECK_TIMED_OUT, Duration.ofSeconds(30), NOW);

        CheckState credit = instance.checks().get(CheckType.CREDIT_SCORE);
        assertThat(credit.isComplete()).isFalse();
        assertThat(credit.attempts()).isEqualTo(1);
        assertThat(credit.lastErrorCode()).isEqualTo(WorkflowReasonCodes.CHECK_TIMED_OUT);
        // Not due now, but due once the backoff elapses. The schedule is in the
        // value, so it survives a restart rather than starting over.
        assertThat(instance.checksDueAt(NOW)).noneMatch(check -> check.type() == CheckType.CREDIT_SCORE);
        assertThat(instance.checksDueAt(NOW.plusSeconds(31)))
                .anyMatch(check -> check.type() == CheckType.CREDIT_SCORE);
    }

    @Test
    @DisplayName("abandoning a check fails the whole assessment rather than deciding without it")
    void abandoningACheckFailsTheAssessment() {
        // Concluding on partial evidence — approving without the AML result
        // because the provider was unreachable — is the failure a lending
        // platform must not have.
        WorkflowInstance instance = started();
        instance.recordCheckOutcome(CheckType.KYC, CheckOutcome.PASSED, "IDENTITY_VERIFIED", null, NOW);

        instance.abandonCheck(CheckType.AML, WorkflowReasonCodes.CHECK_RETRY_BUDGET_EXHAUSTED, NOW);

        assertThat(instance.status()).isEqualTo(WorkflowStatus.FAILED);
        assertThat(instance.recommendation()).isEmpty();
        assertThat(instance.pendingEvents()).hasAtLeastOneElementOfType(WorkflowFailed.class);
    }

    @Test
    @DisplayName("an abandoned check records no outcome, so a provider outage is never read as a refusal")
    void abandonedCheckHasNoOutcome() {
        WorkflowInstance instance = started();

        instance.abandonCheck(CheckType.CREDIT_SCORE, WorkflowReasonCodes.CHECK_RETRY_BUDGET_EXHAUSTED, NOW);

        CheckState credit = instance.checks().get(CheckType.CREDIT_SCORE);
        assertThat(credit.isAbandoned()).isTrue();
        // Crucially null: recording it as FAILED would let the policy treat
        // "the bureau was down" as "the applicant has bad credit".
        assertThat(credit.outcome()).isNull();
    }

    @Test
    @DisplayName("the assessment does not conclude while any check is outstanding")
    void doesNotConcludeEarly() {
        WorkflowInstance instance = started();
        instance.recordCheckOutcome(CheckType.KYC, CheckOutcome.PASSED, "OK", null, NOW);
        instance.recordCheckOutcome(CheckType.AML, CheckOutcome.PASSED, "OK", null, NOW);

        assertThat(instance.concludeIfReady(policy, NOW)).isEmpty();
        assertThat(instance.status()).isEqualTo(WorkflowStatus.RUNNING);
    }

    @Test
    @DisplayName("the assessment concludes once every check has answered")
    void concludesWhenAllChecksAnswer() {
        WorkflowInstance instance = started();
        passAllChecks(instance, 800);

        assertThat(instance.concludeIfReady(policy, NOW)).contains(Recommendation.APPROVE);
        assertThat(instance.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(instance.reasonCode()).isEqualTo(WorkflowReasonCodes.ALL_CHECKS_PASSED);
        assertThat(instance.pendingEvents()).hasAtLeastOneElementOfType(WorkflowCompleted.class);
    }

    @Test
    @DisplayName("concluding twice is a no-op, so a redelivered advance cannot decide again")
    void concludingIsIdempotent() {
        WorkflowInstance instance = started();
        passAllChecks(instance, 800);
        instance.concludeIfReady(policy, NOW);
        long versionAfterFirst = instance.version();

        assertThat(instance.concludeIfReady(policy, NOW)).isEmpty();
        assertThat(instance.version()).isEqualTo(versionAfterFirst);
    }

    @Test
    @DisplayName("a terminated assessment refuses further check outcomes")
    void terminalWorkflowRefusesFurtherOutcomes() {
        WorkflowInstance instance = started();
        instance.abandonCheck(CheckType.AML, WorkflowReasonCodes.CHECK_RETRY_BUDGET_EXHAUSTED, NOW);

        assertThatThrownBy(() ->
                        instance.recordCheckOutcome(CheckType.KYC, CheckOutcome.PASSED, "OK", null, NOW))
                .isInstanceOf(IllegalWorkflowStateException.class);
    }

    @Test
    @DisplayName("recording an outcome for a check that already answered is refused")
    void checkCannotAnswerTwice() {
        WorkflowInstance instance = started();
        instance.recordCheckOutcome(CheckType.KYC, CheckOutcome.PASSED, "OK", null, NOW);

        assertThatThrownBy(() ->
                        instance.recordCheckOutcome(CheckType.KYC, CheckOutcome.FAILED, "CHANGED_MY_MIND", null, NOW))
                .isInstanceOf(IllegalWorkflowStateException.class);
    }

    @Test
    @DisplayName("every event carries the version it produced, in increasing order")
    void eventVersionsIncreaseMonotonically() {
        WorkflowInstance instance = started();
        passAllChecks(instance, 800);
        instance.concludeIfReady(policy, NOW);

        assertThat(instance.pendingEvents().stream()
                        .map(com.example.los.workflow.domain.event.WorkflowDomainEvent::aggregateVersion)
                        .toList())
                .isSorted();
    }

    @Test
    @DisplayName("draining returns events once and leaves the instance empty")
    void drainingIsIdempotent() {
        WorkflowInstance instance = started();

        assertThat(instance.drainPendingEvents()).isNotEmpty();
        assertThat(instance.drainPendingEvents()).isEmpty();
    }

    @Test
    @DisplayName("the instance never prints the applicant reference or the risk inputs")
    void toStringIsSafe() {
        // The reference is a pseudonym rather than personal data, but an amount
        // plus a pseudonym is more identifying together than either alone.
        String printed = started().toString();

        assertThat(printed).contains(APPLICATION_ID).doesNotContain(APPLICANT_REFERENCE).doesNotContain("1000000");
    }

    private static void passAllChecks(WorkflowInstance instance, int creditScore) {
        instance.recordCheckOutcome(CheckType.KYC, CheckOutcome.PASSED, "IDENTITY_VERIFIED", null, NOW);
        instance.recordCheckOutcome(CheckType.AML, CheckOutcome.PASSED, "NO_SANCTIONS_MATCH", null, NOW);
        instance.recordCheckOutcome(CheckType.FRAUD, CheckOutcome.PASSED, "FRAUD_RISK_ACCEPTABLE", 100, NOW);
        instance.recordCheckOutcome(CheckType.CREDIT_SCORE, CheckOutcome.PASSED, "SCORE_RETRIEVED", creditScore, NOW);
    }
}
