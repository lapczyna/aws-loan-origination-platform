package com.example.los.workflow.adapter.out.checks;

import java.util.Locale;

/**
 * The behaviours a simulated provider can exhibit.
 *
 * <p><strong>These simulators are not production integrations and are never
 * described as such.</strong> Their purpose is to make every failure mode a real
 * provider can exhibit reachable from a test and from the local Compose stack,
 * so the retry, circuit-breaker and dead-letter paths are exercised rather than
 * merely written.
 *
 * <p>The scenario for a given check is chosen deterministically from the
 * applicant reference, so the same synthetic applicant always produces the same
 * behaviour. Determinism is what makes the end-to-end tests reproducible: a
 * simulator that rolled a die would give a suite that fails one run in twenty
 * for no reason anyone could reproduce.
 */
public enum SimulatedScenario {

    /** The provider answers, and the applicant satisfies the check. */
    SUCCESS,

    /** The provider answers, and the applicant does not satisfy the check. Never retried. */
    BUSINESS_REJECTION,

    /** The provider answers but cannot decide. Routes the application to a human. */
    INCONCLUSIVE,

    /** The provider does not answer within the timeout. Retryable. */
    TIMEOUT,

    /** The provider is briefly unavailable and recovers. Retryable. */
    TRANSIENT_FAILURE,

    /** The provider rejects the request in a way retrying cannot fix. Not retried. */
    PERMANENT_FAILURE,

    /** The provider answers correctly but slowly, close to the timeout. */
    DELAYED_SUCCESS;

    /**
     * Resolves a scenario from the product code first, then from the applicant
     * reference, defaulting to {@link #SUCCESS}.
     *
     * <p><b>Why the product code and not only the applicant reference.</b> The
     * applicant reference that reaches this context is an HMAC pseudonym: an
     * opaque string that will never contain a scenario name. A caller coming
     * through the real API therefore cannot select a behaviour by choosing an
     * applicant, and every failure mode would be reachable only by calling this
     * context directly -- which is to say, only by a test that skips the parts
     * most worth testing.
     *
     * <p>The product code is supplied by the client, travels the whole way on
     * the submission event, and contains no personal data. Matching on it makes
     * every simulated failure mode reachable through the front door, which is
     * what lets the end-to-end suite cover a permanent rejection, a transient
     * failure that recovers, and a route to manual review rather than only the
     * happy path.
     *
     * <p>The applicant-reference form is kept because the workflow context's own
     * tests drive the use cases directly and choose the reference themselves.
     *
     * @param productCode        the client-supplied product identifier, may be null
     * @param applicantReference the pseudonymous applicant reference, may be null
     */
    public static SimulatedScenario forRequest(String productCode, String applicantReference) {
        SimulatedScenario fromProductCode = match(productCode);
        if (fromProductCode != null) {
            return fromProductCode;
        }
        SimulatedScenario fromReference = match(applicantReference);
        return fromReference != null ? fromReference : SUCCESS;
    }

    /**
     * Resolves a scenario from an explicit marker embedded in the applicant
     * reference, or {@link #SUCCESS} when there is none.
     */
    public static SimulatedScenario forReference(String applicantReference) {
        SimulatedScenario matched = match(applicantReference);
        return matched != null ? matched : SUCCESS;
    }

    /** The scenario named in the value, or null when it names none. */
    private static SimulatedScenario match(String value) {
        if (value == null) {
            return null;
        }
        String normalised = value.toUpperCase(Locale.ROOT);
        for (SimulatedScenario scenario : values()) {
            if (normalised.contains(scenario.name())) {
                return scenario;
            }
        }
        return null;
    }
}
