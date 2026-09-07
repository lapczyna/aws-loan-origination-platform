package com.example.los.workflow.usecase.port;

import com.example.los.events.vocabulary.CheckType;

/**
 * Outbound port for running one external check with the platform's resilience
 * policy applied.
 *
 * <p>Separate from {@link ExternalCheckPort} because the two answer different
 * questions. {@code ExternalCheckPort} is "how do I talk to this provider" and
 * has one implementation per check. This is "how does the platform call a
 * provider safely" — timeout, bulkhead, circuit breaker — and has one
 * implementation for all of them.
 *
 * <p>Keeping it a port rather than letting the use case reference the decorator
 * directly means the workflow can be exercised without the resilience machinery
 * (a test that wants a provider to fail immediately does not want to wait for a
 * three-second timeout first), and the policy can be changed without touching the
 * use case at all.
 */
public interface CheckExecutor {

    /**
     * Performs a check with timeout, bulkhead and circuit breaker applied.
     *
     * @throws TransientCheckFailure on timeout, bulkhead rejection, or an open breaker
     * @throws PermanentCheckFailure when the provider refused in a way retrying cannot fix
     */
    ExternalCheckPort.CheckAnswer invoke(CheckType type, ExternalCheckPort.CheckRequest request);
}
