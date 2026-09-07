package com.example.los.workflow.adapter.out.checks;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.los.events.vocabulary.CheckType;

/**
 * SIMULATED anti-money-laundering screening.
 *
 * <p>A failure here is regulatory and is never overridden by a commercial
 * consideration; see {@code AssessmentPolicy}.
 */
@Component
class SimulatedAmlProvider extends SimulatedCheckProvider {

    SimulatedAmlProvider(
            @Value("${los.checks.aml.simulated-latency:PT0.05S}") Duration latency,
            @Value("${los.checks.aml.delayed-latency:PT2S}") Duration delayedLatency) {
        super(latency, delayedLatency);
    }

    @Override
    public CheckType supports() {
        return CheckType.AML;
    }

    @Override
    protected CheckAnswer pass(CheckRequest request) {
        return passed("NO_SANCTIONS_MATCH");
    }

    @Override
    protected String businessRejectionReasonCode() {
        return "SANCTIONS_LIST_MATCH";
    }
}
