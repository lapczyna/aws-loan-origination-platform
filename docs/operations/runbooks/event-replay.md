> **Nothing in this repository has been deployed.** These runbooks describe how
> the platform would be operated. No alarm has ever fired, because no alarm has
> ever existed.

# Runbook: replaying dead-lettered events

**Alarm:** `dead-letter-events`

---

## Before anything else

**Replay is safe here, and it is worth knowing exactly why.** Every consumer on
this platform is idempotent: each keeps a de-duplication ledger keyed on the
event identifier, and re-delivering an event it has already applied is recorded
and ignored. Kafka guarantees at-least-once delivery, so this is not a special
case for replay — it is the normal condition, and it is covered by the
end-to-end suite.

What replay does **not** fix is an event that was wrong when it was written.
Republishing it just applies the wrong thing again.

## What is in the dead-letter topic

`los.dlq.v1` holds events a consumer could not process after exhausting its
retries. Each carries the original envelope, the consumer that failed, a stable
error code, and when it happened.

It carries **no personal data** and no exception message: the error code is a
constant, because a stack trace in a dead-letter topic is a stack trace in
long-term storage.

## Establish why it failed first

Replaying without knowing why is how the same event goes round twice.

| Error code family | Meaning | Replay? |
|---|---|---|
| Deserialisation | The envelope does not match the schema the consumer expects | **No.** Fix the consumer or the producer first; replay after deploying. |
| Downstream unavailable | The database or a dependency was down | **Yes**, once it is back. |
| Constraint violation | The consumer's own state rejected it | **Investigate.** Often a symptom of out-of-order delivery that has since resolved. |
| Business rule | The event asks for something the aggregate refuses | **No.** Replay will be refused identically. Escalate. |

## Replaying

1. **Confirm the cause is fixed.** Replaying into the same failure re-parks the
   event and costs you the second attempt for nothing.
2. **Replay a single event first**, and confirm it was applied — not merely
   consumed. The de-duplication ledger will record it; the effect is what you are
   checking.
3. **Then the rest, in the order they were produced.** Ordering matters per
   application: an outcome applied before the submission it belongs to is
   refused, correctly, and lands straight back in the dead-letter topic.
4. **Confirm the alarm clears** and the applications involved reach a terminal
   state.

Replay is a deliberate, audited action. It is not automatic, and it is not a
scheduled job: an automatic replay loop turns one bad event into a permanent
cycle nobody notices.

## What replay is not for

- **Not for skipping a backlog.** That is consumer lag; see
  [`kafka-consumer-lag.md`](kafka-consumer-lag.md).
- **Not for re-running a decision.** A decision is recorded once and the audit
  trail says so. Re-deciding needs a new decision, with a reviewer's name on it.
- **Not for repairing the audit trail.** The trail is append-only and
  hash-chained. If it is missing records, the fix is to let the audit consumer
  catch up, never to inject records by hand.

## Related

[`stuck-outbox.md`](stuck-outbox.md) · [`failed-workflow.md`](failed-workflow.md)
