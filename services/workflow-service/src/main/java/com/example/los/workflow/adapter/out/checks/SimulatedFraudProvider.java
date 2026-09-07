package com.example.los.workflow.adapter.out.checks;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.los.events.vocabulary.CheckType;

/** SIMULATED fraud screening. */
@Component
class SimulatedFraudProvider extends SimulatedCheckProvider {

    SimulatedFraudProvider(
            @Value("${los.checks.fraud.simulated-latency:PT0.05S}") Duration latency,
            @Value("${los.checks.fraud.delayed-latency:PT2S}") Duration delayedLatency) {
        super(latency, delayedLatency);
    }

    @Override
    public CheckType supports() {
        return CheckType.FRAUD;
    }

    @Override
    protected CheckAnswer pass(CheckRequest request) {
        // A low, stable risk score for an applicant the simulator considers
        // legitimate. Real scoring would weigh device, velocity and network
        // signals this port has no access to.
        int riskScore = stableScore(request.applicantReference(), 10, 300);
        return passed("FRAUD_RISK_ACCEPTABLE", riskScore);
    }

    @Override
    protected String businessRejectionReasonCode() {
        return "FRAUD_RISK_TOO_HIGH";
    }
}
