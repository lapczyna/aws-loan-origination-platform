package com.example.los.workflow.adapter.out.checks;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.los.events.vocabulary.CheckOutcome;
import com.example.los.events.vocabulary.CheckType;
import com.example.los.workflow.domain.model.WorkflowReasonCodes;
import com.example.los.workflow.usecase.port.ExternalCheckPort;
import com.example.los.workflow.usecase.port.PermanentCheckFailure;
import com.example.los.workflow.usecase.port.TransientCheckFailure;

/**
 * Shared behaviour for the four simulated providers.
 *
 * <p><strong>SIMULATION ONLY.</strong> Nothing here contacts a real KYC, AML,
 * fraud or credit bureau system, and no adapter in this repository does. A real
 * adapter would replace one of the subclasses and would need to honour exactly
 * the contract documented on {@link ExternalCheckPort} — which is the point of
 * writing the simulators against that contract rather than stubbing the workflow.
 *
 * <p>Subclasses supply only what differs: the check type and how a successful
 * answer is scored.
 */
abstract class SimulatedCheckProvider implements ExternalCheckPort {

    private static final Logger log = LoggerFactory.getLogger(SimulatedCheckProvider.class);

    /**
     * How long a transient failure lasts before the provider "recovers".
     *
     * <p>The first attempt fails and later attempts succeed, so a test can
     * observe a retry actually working rather than only that a retry happened.
     */
    private static final int TRANSIENT_FAILURE_RECOVERS_AFTER_ATTEMPTS = 1;

    private final Duration simulatedLatency;
    private final Duration delayedResponseLatency;

    protected SimulatedCheckProvider(Duration simulatedLatency, Duration delayedResponseLatency) {
        this.simulatedLatency = simulatedLatency;
        this.delayedResponseLatency = delayedResponseLatency;
    }

    @Override
    public final CheckAnswer perform(CheckRequest request) {
        SimulatedScenario scenario = SimulatedScenario.forRequest(
                request.inputs() == null ? null : request.inputs().productCode(), request.applicantReference());

        // Identifiers and the scenario only. The request carries no personal data
        // by construction, and the inputs are not logged either.
        log.debug(
                "Simulated {} check. applicationId={} scenario={} attempt={}",
                supports(),
                request.applicationId(),
                scenario,
                request.attempt());

        return switch (scenario) {
            case SUCCESS -> {
                sleep(simulatedLatency);
                yield pass(request);
            }

            case DELAYED_SUCCESS -> {
                // Slow but correct. Exercises the timeout boundary without
                // guaranteeing a failure on either side of it.
                sleep(delayedResponseLatency);
                yield pass(request);
            }

            case BUSINESS_REJECTION -> {
                sleep(simulatedLatency);
                yield new CheckAnswer(CheckOutcome.FAILED, businessRejectionReasonCode(), null);
            }

            case INCONCLUSIVE -> {
                sleep(simulatedLatency);
                yield new CheckAnswer(CheckOutcome.INCONCLUSIVE, WorkflowReasonCodes.CHECK_INCONCLUSIVE, null);
            }

            case TIMEOUT -> {
                // Never answers. The caller's timeout is what ends this, which is
                // exactly how an unresponsive provider behaves.
                sleep(Duration.ofMinutes(5));
                yield pass(request);
            }

            case TRANSIENT_FAILURE -> {
                if (request.attempt() <= TRANSIENT_FAILURE_RECOVERS_AFTER_ATTEMPTS) {
                    throw new TransientCheckFailure(
                            WorkflowReasonCodes.CHECK_TIMED_OUT,
                            "Simulated provider for " + supports() + " is temporarily unavailable");
                }
                sleep(simulatedLatency);
                yield pass(request);
            }

            case PERMANENT_FAILURE -> throw new PermanentCheckFailure(
                    WorkflowReasonCodes.CHECK_PERMANENT_ERROR,
                    "Simulated provider for " + supports() + " rejected the request permanently");
        };
    }

    /** The answer for an applicant who satisfies this check. */
    protected abstract CheckAnswer pass(CheckRequest request);

    /** The stable reason code this check uses when the applicant does not satisfy it. */
    protected abstract String businessRejectionReasonCode();

    /**
     * Produces a stable pseudo-score in the given range from the applicant
     * reference.
     *
     * <p>Derived from the reference rather than randomly generated so that the
     * same synthetic applicant always scores the same, which is what makes the
     * end-to-end assertions deterministic.
     */
    protected static int stableScore(String applicantReference, int minimum, int maximum) {
        int span = maximum - minimum + 1;
        int hash = Math.abs(applicantReference.hashCode() % span);
        return minimum + hash;
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            // Small jitter so simulated providers do not all respond on the same
            // millisecond, which would hide ordering bugs the real world exposes.
            long millis = duration.toMillis();
            long jittered = millis + ThreadLocalRandom.current().nextLong(0, Math.max(1, millis / 4 + 1));
            Thread.sleep(jittered);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TransientCheckFailure(
                    WorkflowReasonCodes.CHECK_TIMED_OUT, "Simulated check was interrupted", e);
        }
    }

    /** Convenience for subclasses that pass without a score. */
    protected static CheckAnswer passed(String reasonCode) {
        return new CheckAnswer(CheckOutcome.PASSED, reasonCode, null);
    }

    /** Convenience for subclasses that pass with a score. */
    protected static CheckAnswer passed(String reasonCode, int score) {
        return new CheckAnswer(CheckOutcome.PASSED, reasonCode, score);
    }

    /** Present so subclasses can name their own type in log messages. */
    @Override
    public abstract CheckType supports();
}
