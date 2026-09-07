package com.example.los.application.adapter.out.messaging;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.example.los.application.adapter.out.persistence.OutboxRelayGateway;
import com.example.los.application.adapter.out.persistence.OutboxRelayGateway.PendingEvent;

/**
 * Drains the transactional outbox to Kafka.
 *
 * <p>This is the second half of the outbox pattern. The first half guaranteed
 * that an event exists whenever the state change it describes was committed;
 * this half guarantees the event eventually reaches the broker.
 *
 * <h2>Why it is safe</h2>
 *
 * <ul>
 *   <li><b>No loss.</b> A row is only marked published after the broker
 *       acknowledges it. A crash between publish and mark leaves the row PENDING
 *       and it is published again — at-least-once, never at-most-once.
 *   <li><b>Duplicates are expected.</b> Because of the above, a consumer will
 *       occasionally see the same event twice. Every consumer de-duplicates on
 *       {@code eventId}, which is what makes at-least-once acceptable.
 *   <li><b>Broker outage is survivable.</b> A failed send schedules a retry with
 *       exponential backoff and jitter. Events accumulate in PostgreSQL, which is
 *       durable, and drain when the broker returns. Nothing is dropped and no
 *       request thread is blocked, because publication never happens on the
 *       request path.
 *   <li><b>Multiple replicas cooperate.</b> The claim query uses
 *       {@code FOR UPDATE SKIP LOCKED}, so replicas take disjoint batches.
 * </ul>
 *
 * <h2>Ordering</h2>
 *
 * <p>Batches are claimed in creation order and sent with the application
 * identifier as the partition key, so events about one application arrive on one
 * partition in order. Ordering across different applications is neither
 * guaranteed nor needed.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /** Error codes are stable and safe. An exception message is never used as one. */
    static final String ERROR_BROKER_UNAVAILABLE = "BROKER_UNAVAILABLE";

    static final String ERROR_RETRY_BUDGET_EXHAUSTED = "PUBLISH_RETRY_BUDGET_EXHAUSTED";

    private final OutboxRelayGateway gateway;
    private final KafkaTemplate<String, String> kafka;
    private final Clock clock;
    private final int batchSize;
    private final int maxAttempts;
    private final Duration baseBackoff;
    private final Duration maxBackoff;
    private final Duration sendTimeout;

    public OutboxPublisher(
            OutboxRelayGateway gateway,
            KafkaTemplate<String, String> kafka,
            Clock clock,
            @Value("${los.outbox.batch-size:100}") int batchSize,
            @Value("${los.outbox.max-attempts:10}") int maxAttempts,
            @Value("${los.outbox.base-backoff:PT1S}") Duration baseBackoff,
            @Value("${los.outbox.max-backoff:PT5M}") Duration maxBackoff,
            @Value("${los.outbox.send-timeout:PT10S}") Duration sendTimeout) {
        this.gateway = gateway;
        this.kafka = kafka;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.baseBackoff = baseBackoff;
        this.maxBackoff = maxBackoff;
        this.sendTimeout = sendTimeout;
    }

    /**
     * Publishes one batch.
     *
     * <p>Runs in its own transaction. The claim holds a row lock for the duration
     * of the batch, which is why the batch is bounded and the send timeout is
     * short: a long batch would hold locks and delay every other replica.
     *
     * @return the number of events successfully published
     */
    @Scheduled(fixedDelayString = "${los.outbox.poll-interval:PT1S}")
    @Transactional
    public int publishBatch() {
        List<PendingEvent> claimed = gateway.claimPending(batchSize);
        if (claimed.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (PendingEvent event : claimed) {
            if (publish(event)) {
                published++;
            }
        }

        if (published < claimed.size()) {
            log.warn(
                    "Outbox batch partially published: published={} claimed={}",
                    published,
                    claimed.size());
        }
        return published;
    }

    private boolean publish(PendingEvent event) {
        try {
            // Blocking on the send is deliberate. The row must not be marked
            // published until the broker has acknowledged it, and this runs on a
            // scheduler thread, never on a request thread.
            kafka.send(event.topic(), event.partitionKey(), event.payload())
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);

            gateway.markPublished(event.id(), clock.instant());
            return true;

        } catch (InterruptedException e) {
            // Restore the flag and stop: the JVM is shutting down and the
            // remaining events stay PENDING for the next instance to pick up.
            Thread.currentThread().interrupt();
            gateway.markAttemptFailed(event.id(), ERROR_BROKER_UNAVAILABLE, clock.instant().plus(baseBackoff));
            return false;

        } catch (Exception e) {
            // Deliberately broad, and deliberately the only broad catch in this
            // class: any failure to reach the broker must leave the row durable
            // and retryable rather than propagating and rolling back the whole
            // batch, which would also undo the successes before it.
            handleFailure(event, e);
            return false;
        }
    }

    private void handleFailure(PendingEvent event, Exception cause) {
        int nextAttempt = event.attempts() + 1;

        if (nextAttempt >= maxAttempts) {
            gateway.markPermanentlyFailed(event.id(), ERROR_RETRY_BUDGET_EXHAUSTED);
            // Logged at error with identifiers only: no payload, no broker
            // address, no exception object that might carry either.
            log.error(
                    "Outbox event permanently failed after {} attempts. eventId={} eventType={} errorCode={} cause={}",
                    nextAttempt,
                    event.id(),
                    event.eventType(),
                    ERROR_RETRY_BUDGET_EXHAUSTED,
                    cause.getClass().getSimpleName());
            return;
        }

        gateway.markAttemptFailed(event.id(), ERROR_BROKER_UNAVAILABLE, clock.instant().plus(backoffFor(nextAttempt)));
        log.warn(
                "Outbox publication attempt failed, will retry. eventId={} attempt={} errorCode={} cause={}",
                event.id(),
                nextAttempt,
                ERROR_BROKER_UNAVAILABLE,
                cause.getClass().getSimpleName());
    }

    /**
     * Exponential backoff with full jitter.
     *
     * <p>The jitter is not decoration. Without it, every replica that failed
     * during the same broker outage retries at the same instant, and the broker
     * is hit by a synchronised thundering herd exactly as it is recovering.
     * Spreading retries uniformly across the interval is what lets the broker
     * come back.
     */
    private Duration backoffFor(int attempt) {
        long exponentialMillis = baseBackoff.toMillis() * (1L << Math.min(attempt - 1, 20));
        long cappedMillis = Math.min(exponentialMillis, maxBackoff.toMillis());
        long jitteredMillis = ThreadLocalRandom.current().nextLong(baseBackoff.toMillis(), cappedMillis + 1);
        return Duration.ofMillis(jitteredMillis);
    }
}
