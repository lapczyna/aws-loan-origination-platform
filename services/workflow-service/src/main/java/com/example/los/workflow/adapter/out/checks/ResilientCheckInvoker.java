package com.example.los.workflow.adapter.out.checks;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.example.los.events.vocabulary.CheckType;
import com.example.los.workflow.domain.model.WorkflowReasonCodes;
import com.example.los.workflow.usecase.port.CheckExecutor;
import com.example.los.workflow.usecase.port.ExternalCheckPort;
import com.example.los.workflow.usecase.port.PermanentCheckFailure;
import com.example.los.workflow.usecase.port.TransientCheckFailure;

/**
 * Wraps every external check call in a timeout, a circuit breaker and a bulkhead.
 *
 * <p>Three protections, each against a different failure:
 *
 * <p><b>Timeout.</b> A provider that accepts the connection and then never
 * answers is worse than one that refuses outright: without a deadline the
 * calling thread is held indefinitely, and enough of them will exhaust the pool
 * and stall assessments that have nothing to do with that provider. Every call
 * therefore runs with an explicit, short deadline.
 *
 * <p><b>Bulkhead.</b> A bounded thread pool per check type, so a slow provider
 * can consume only its own share of concurrency. Sharing one pool across all four
 * checks would let a degraded credit bureau starve KYC, AML and fraud of threads
 * — one slow dependency taking the whole platform down with it. The queue is
 * bounded too: an unbounded queue does not prevent overload, it just converts a
 * fast rejection into a slow one and hides the problem until memory runs out.
 *
 * <p><b>Circuit breaker.</b> Once a provider is clearly down, continuing to send
 * it traffic helps nobody: each call waits for the full timeout, and the load
 * keeps the provider from recovering. The breaker fails fast instead, and the
 * failure is reported as transient so the workflow reschedules rather than
 * abandoning the applicant.
 *
 * <p>Retries deliberately live <em>outside</em> this class, in the workflow's
 * durable schedule. Retrying in-process would hold a thread across the backoff
 * and lose the retry state on restart; retrying through the database survives
 * both.
 */
@Component
public class ResilientCheckInvoker implements CheckExecutor {

    private static final Logger log = LoggerFactory.getLogger(ResilientCheckInvoker.class);

    private final Map<CheckType, ExternalCheckPort> providers = new EnumMap<>(CheckType.class);
    private final Map<CheckType, ExecutorService> bulkheads = new EnumMap<>(CheckType.class);
    private final Map<CheckType, CircuitBreaker> breakers = new EnumMap<>(CheckType.class);
    private final Duration callTimeout;

    public ResilientCheckInvoker(
            List<ExternalCheckPort> checkProviders,
            MeterRegistry meterRegistry,
            @Value("${los.checks.timeout:PT3S}") Duration callTimeout,
            @Value("${los.checks.bulkhead-concurrency:8}") int bulkheadConcurrency,
            @Value("${los.checks.bulkhead-queue-capacity:32}") int bulkheadQueueCapacity,
            @Value("${los.checks.circuit-breaker.failure-rate-threshold:50}") float failureRateThreshold,
            @Value("${los.checks.circuit-breaker.wait-duration-in-open-state:PT30S}") Duration waitInOpenState,
            @Value("${los.checks.circuit-breaker.sliding-window-size:20}") int slidingWindowSize,
            @Value("${los.checks.circuit-breaker.minimum-calls:8}") int minimumCalls) {

        CircuitBreakerConfig breakerConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(waitInOpenState)
                .slidingWindowSize(slidingWindowSize)
                // Without a minimum, one failure among the first two calls after
                // startup opens the breaker at a 50% failure rate and blocks a
                // provider that is perfectly healthy.
                .minimumNumberOfCalls(minimumCalls)
                .permittedNumberOfCallsInHalfOpenState(Math.max(2, minimumCalls / 4))
                // A business rejection is a successful call. Counting it as a
                // failure would open the breaker on a run of legitimately
                // declined applicants and stop assessing anyone.
                .ignoreExceptions(PermanentCheckFailure.class)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(breakerConfig);
        this.callTimeout = callTimeout;

        for (ExternalCheckPort provider : checkProviders) {
            CheckType type = provider.supports();
            providers.put(type, provider);
            breakers.put(type, registry.circuitBreaker("check-" + type.name().toLowerCase(java.util.Locale.ROOT)));
            bulkheads.put(type, boundedPool(type, bulkheadConcurrency, bulkheadQueueCapacity));
        }

        registerMetrics(meterRegistry);
    }

    /**
     * Performs a check with the full protection stack applied.
     *
     * @throws TransientCheckFailure on timeout, rejection, or an open breaker
     * @throws PermanentCheckFailure when the provider refused in a way retrying cannot fix
     */
    @Override
    public ExternalCheckPort.CheckAnswer invoke(CheckType type, ExternalCheckPort.CheckRequest request) {
        ExternalCheckPort provider = providers.get(type);
        if (provider == null) {
            throw new PermanentCheckFailure(
                    WorkflowReasonCodes.CHECK_PERMANENT_ERROR, "No adapter is registered for check type " + type);
        }

        CircuitBreaker breaker = breakers.get(type);
        if (!breaker.tryAcquirePermission()) {
            // Fail fast and reschedule. Reported as transient because the
            // provider may well be healthy by the time the workflow retries.
            throw new TransientCheckFailure(
                    WorkflowReasonCodes.CHECK_CIRCUIT_OPEN,
                    "Circuit breaker for " + type + " is open; the provider is being given time to recover");
        }

        long startedNanos = System.nanoTime();
        Future<ExternalCheckPort.CheckAnswer> pending;
        try {
            pending = bulkheads.get(type).submit(() -> provider.perform(request));
        } catch (RejectedExecutionException e) {
            // The bulkhead is full. Shedding load here is the point: queuing
            // further would delay every other check behind a provider that is
            // already saturated.
            breaker.releasePermission();
            throw new TransientCheckFailure(
                    WorkflowReasonCodes.CHECK_TIMED_OUT, "Concurrency limit reached for " + type + " checks");
        }

        try {
            ExternalCheckPort.CheckAnswer answer = pending.get(callTimeout.toMillis(), TimeUnit.MILLISECONDS);
            breaker.onSuccess(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS);
            return answer;

        } catch (TimeoutException e) {
            // Cancel with interruption so the worker thread is reclaimed rather
            // than left blocked on a provider that will never answer.
            pending.cancel(true);
            TransientCheckFailure failure = new TransientCheckFailure(
                    WorkflowReasonCodes.CHECK_TIMED_OUT,
                    type + " check did not answer within " + callTimeout.toMillis() + "ms");
            breaker.onError(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS, failure);
            throw failure;

        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            breaker.releasePermission();
            throw new TransientCheckFailure(
                    WorkflowReasonCodes.CHECK_TIMED_OUT, type + " check was interrupted during shutdown", e);

        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            breaker.onError(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS, cause);

            // Unwrapped so the workflow sees the adapter's own classification
            // rather than an ExecutionException it would have to guess about.
            switch (cause) {
                case PermanentCheckFailure permanent -> throw permanent;
                case TransientCheckFailure transient_ -> throw transient_;
                default -> throw new TransientCheckFailure(
                        WorkflowReasonCodes.CHECK_TIMED_OUT,
                        type + " check failed with an unexpected error: " + cause.getClass().getSimpleName());
            }
        }
    }

    private static ExecutorService boundedPool(CheckType type, int concurrency, int queueCapacity) {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                concurrency,
                concurrency,
                0L,
                TimeUnit.MILLISECONDS,
                // Bounded on purpose. An unbounded queue turns overload into
                // unbounded latency and eventually an OutOfMemoryError, instead
                // of the fast, visible rejection the caller can act on.
                new LinkedBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread thread = new Thread(runnable, "check-" + type.name().toLowerCase(java.util.Locale.ROOT));
                    thread.setDaemon(true);
                    return thread;
                });
        executor.allowCoreThreadTimeOut(false);
        return executor;
    }

    private void registerMetrics(MeterRegistry meterRegistry) {
        breakers.forEach((type, breaker) -> meterRegistry.gauge(
                "los.check.circuit.open",
                io.micrometer.core.instrument.Tags.of("check", type.name()),
                breaker,
                cb -> cb.getState() == CircuitBreaker.State.OPEN ? 1d : 0d));
    }

    /**
     * Shuts the bulkheads down so a pod termination does not strand a check
     * mid-flight and leave the workflow's lease to expire before anything
     * notices.
     */
    @PreDestroy
    void shutdown() {
        bulkheads.forEach((type, executor) -> {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    log.warn("Bulkhead for {} checks did not terminate within the grace period", type);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }
}
