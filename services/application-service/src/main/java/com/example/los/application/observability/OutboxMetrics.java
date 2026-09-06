package com.example.los.application.observability;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.stereotype.Component;

import com.example.los.application.adapter.out.persistence.OutboxRelayGateway;

/**
 * Publishes the outbox signals that operations actually alarms on.
 *
 * <p>Three gauges, each answering a different question:
 *
 * <ul>
 *   <li><b>Backlog size</b> answers "how much is waiting?". On its own it is
 *       ambiguous: a large backlog draining quickly is healthy, and a small one
 *       that never drains is not.
 *   <li><b>Age of the oldest unpublished event</b> answers "how long has anything
 *       been stuck?". This is the signal worth paging on. It rises the moment
 *       publication stalls, regardless of volume, and it maps directly to
 *       customer impact: an application whose event is stuck is an application
 *       whose assessment has not started.
 *   <li><b>Permanently failed count</b> answers "how much needs a human?". Any
 *       non-zero value means the dead-letter runbook has work waiting.
 * </ul>
 *
 * <p>Gauges are registered with supplier functions, so Micrometer samples them on
 * scrape rather than this class polling the database on a timer. The queries are
 * indexed counts against a partial index, so the cost per scrape is small.
 */
@Component
class OutboxMetrics {

    private final OutboxRelayGateway gateway;
    private final Clock clock;

    OutboxMetrics(MeterRegistry registry, OutboxRelayGateway gateway, Clock clock) {
        this.gateway = gateway;
        this.clock = clock;

        registry.gauge(
                "los.outbox.pending",
                Tags.of("aggregate", "LoanApplication"),
                this,
                metrics -> metrics.gateway.pendingCount());

        registry.gauge(
                "los.outbox.oldest.age.seconds",
                Tags.of("aggregate", "LoanApplication"),
                this,
                OutboxMetrics::oldestPendingAgeSeconds);

        registry.gauge(
                "los.outbox.permanently.failed",
                Tags.of("aggregate", "LoanApplication"),
                this,
                metrics -> metrics.gateway.permanentlyFailedCount());
    }

    /** Zero when nothing is waiting, which is the correct reading for an empty outbox. */
    private static double oldestPendingAgeSeconds(OutboxMetrics metrics) {
        Instant oldest = metrics.gateway.oldestUnpublishedAt().orElse(null);
        if (oldest == null) {
            return 0d;
        }
        return Duration.between(oldest, metrics.clock.instant()).toSeconds();
    }
}
