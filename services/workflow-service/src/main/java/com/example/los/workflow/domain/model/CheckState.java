package com.example.los.workflow.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import com.example.los.events.vocabulary.CheckOutcome;
import com.example.los.events.vocabulary.CheckType;

/**
 * The durable state of one external check.
 *
 * <p>Immutable: every transition returns a new instance. Retry state — the
 * attempt count, the last error code and the instant of the next attempt — is
 * part of the value rather than kept in memory, which is what allows a workflow
 * to survive a pod restart mid-retry and resume on the correct schedule instead
 * of starting its backoff again from zero.
 *
 * @param type          which check this is
 * @param outcome       the answer, once the external system gave one
 * @param reasonCode    stable, safe reason code for the outcome
 * @param score         normalised risk score where the check produces one
 * @param attempts      how many attempts have been made
 * @param lastErrorCode safe error code of the most recent technical failure
 * @param nextAttemptAt when this check should next run; null once it is complete
 * @param completedAt   when a definitive outcome was recorded
 */
public record CheckState(
        CheckType type,
        CheckOutcome outcome,
        String reasonCode,
        Integer score,
        int attempts,
        String lastErrorCode,
        Instant nextAttemptAt,
        Instant completedAt) {

    /** Marks a check that gave up. Distinct from FAILED, which is a business answer. */
    public static final String ABANDONED_MARKER = "ABANDONED";

    public CheckState {
        Objects.requireNonNull(type, "type must not be null");
        if (attempts < 0) {
            throw new IllegalArgumentException("attempts must not be negative");
        }
    }

    /** A check that has not run yet, due immediately. */
    public static CheckState scheduled(CheckType type, Instant now) {
        return new CheckState(type, null, null, null, 0, null, now, null);
    }

    /** Records a definitive answer. Terminal: the check will not run again. */
    public CheckState completed(CheckOutcome result, String resultReasonCode, Integer resultScore, Instant now) {
        return new CheckState(type, result, resultReasonCode, resultScore, attempts + 1, null, null, now);
    }

    /** Records a technical failure and schedules the next attempt. */
    public CheckState failedAttempt(String errorCode, Instant retryAt, Instant now) {
        return new CheckState(type, null, null, null, attempts + 1, errorCode, retryAt, null);
    }

    /**
     * Records that the retry budget is exhausted.
     *
     * <p>Deliberately does not set an outcome. Recording ABANDONED as, say, a
     * failed check would let the decision engine treat "the credit bureau was
     * down" as "the applicant has bad credit", which is both wrong and unfair.
     */
    public CheckState abandoned(String errorCode, Instant now) {
        return new CheckState(type, null, ABANDONED_MARKER, null, attempts + 1, errorCode, null, now);
    }

    /** True once the check has a definitive answer or has been abandoned. */
    public boolean isComplete() {
        return outcome != null || isAbandoned();
    }

    public boolean isAbandoned() {
        return ABANDONED_MARKER.equals(reasonCode);
    }

    /** True when this check is waiting to run and its scheduled time has arrived. */
    public boolean isDueAt(Instant now) {
        return !isComplete() && nextAttemptAt != null && !nextAttemptAt.isAfter(now);
    }

    public Optional<CheckOutcome> answer() {
        return Optional.ofNullable(outcome);
    }
}
