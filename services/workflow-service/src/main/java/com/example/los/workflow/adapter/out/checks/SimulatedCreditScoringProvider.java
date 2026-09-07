package com.example.los.workflow.adapter.out.checks;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.los.events.vocabulary.CheckType;

/**
 * SIMULATED credit bureau lookup.
 *
 * <p>Produces a stable 0-1000 score derived from the applicant reference, so the
 * same synthetic applicant always lands in the same decision band and the
 * end-to-end assertions stay reproducible.
 */
@Component
class SimulatedCreditScoringProvider extends SimulatedCheckProvider {

    private static final int MINIMUM_SIMULATED_SCORE = 400;
    private static final int MAXIMUM_SIMULATED_SCORE = 900;

    SimulatedCreditScoringProvider(
            @Value("${los.checks.credit.simulated-latency:PT0.1S}") Duration latency,
            @Value("${los.checks.credit.delayed-latency:PT2S}") Duration delayedLatency) {
        super(latency, delayedLatency);
    }

    @Override
    public CheckType supports() {
        return CheckType.CREDIT_SCORE;
    }

    @Override
    protected CheckAnswer pass(CheckRequest request) {
        int score = stableScore(request.applicantReference(), MINIMUM_SIMULATED_SCORE, MAXIMUM_SIMULATED_SCORE);
        // The bureau reports the score; interpreting it is the platform's job,
        // and lives in AssessmentPolicy rather than here.
        return passed("SCORE_RETRIEVED", score);
    }

    @Override
    protected String businessRejectionReasonCode() {
        return "NO_CREDIT_FILE";
    }
}
