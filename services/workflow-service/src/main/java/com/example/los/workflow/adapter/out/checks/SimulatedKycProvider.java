package com.example.los.workflow.adapter.out.checks;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.los.events.vocabulary.CheckType;

/**
 * SIMULATED identity verification.
 *
 * <p>A real adapter would call an identity provider with attributes this port
 * deliberately cannot carry, over a separately scoped channel.
 */
@Component
class SimulatedKycProvider extends SimulatedCheckProvider {

    SimulatedKycProvider(
            @Value("${los.checks.kyc.simulated-latency:PT0.05S}") Duration latency,
            @Value("${los.checks.kyc.delayed-latency:PT2S}") Duration delayedLatency) {
        super(latency, delayedLatency);
    }

    @Override
    public CheckType supports() {
        return CheckType.KYC;
    }

    @Override
    protected CheckAnswer pass(CheckRequest request) {
        return passed("IDENTITY_VERIFIED");
    }

    @Override
    protected String businessRejectionReasonCode() {
        return "IDENTITY_NOT_VERIFIED";
    }
}
