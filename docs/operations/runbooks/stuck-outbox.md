> **Nothing in this repository has been deployed.** These runbooks describe how
> the platform would be operated. No alarm has ever fired, because no alarm has
> ever existed.

# Runbook: the outbox is not draining

**Alarms:** `outbox-oldest-age`, `outbox-backlog`, `dead-letter-events`

---

## What the alarm means

An event was written to an outbox table and has not been published to Kafka.

**The event is not lost.** It was written in the same transaction as the state
change that produced it, so the two cannot disagree: either both happened or
neither did. What has stalled is publication, and everything downstream — the
assessment, the audit trail — is waiting on it.

## Read the two alarms together

This is the distinction that decides what you do next:

| Backlog | Oldest age | Meaning |
|---|---|---|
| High | Flat | **Throughput.** More events than the publisher can drain, but it is draining. Usually a burst. |
| High | Rising | **A stall.** Something is stopping publication. This is the real incident. |
| Low | Rising | A **small number of poisoned events** repeatedly failing while everything else flows. |

`dead-letter-events` firing means at least one event has exhausted its retry
budget and is parked in `FAILED`. It is not being retried any more, and it will
not resolve on its own.

## First checks

```sql
-- Where is it stuck, and why?
SELECT status, count(*), min(created_at), max(attempts), last_error_code
FROM application.outbox_event
GROUP BY status, last_error_code
ORDER BY count(*) DESC;
```

Repeat for `workflow.outbox_event` and `document.outbox_event` — each context
has its own, and only one may be affected.

Then, in order:

1. **Is the broker reachable?** `kafka-under-replicated`, MSK connectivity, the
   security group. A broker outage is the most common cause and needs no action
   here: the outbox is doing exactly its job, and publication resumes on its own.
2. **Are the publisher pods running?** A crash-looping pod publishes nothing
   while the API keeps accepting work.
3. **Is `last_error_code` the same for every row?** One repeated code is a
   systemic problem — an authorisation failure, a missing topic, a serialisation
   error. Different codes suggest the broker.

## Likely causes

| Cause | Sign | Action |
|---|---|---|
| Broker unavailable | Connection errors, `under-replicated` also firing | None. Publication resumes. Confirm the age falls once the broker is back. |
| Topic missing | `UNKNOWN_TOPIC_OR_PARTITION` | Create the topic. Auto-creation is deliberately off outside development. |
| IAM authorisation | Authorisation errors | The pod's role lost `kafka-cluster:WriteData`. Check a recent Terraform apply. |
| Publisher not running | No publish attempts at all; `attempts` not increasing | Restart the deployment; check for a crash loop. |
| Poisoned event | One or two rows, `attempts` at the maximum | See below. |

## A poisoned event

An event that cannot be serialised or is rejected by the broker will fail every
attempt until its budget is exhausted and it is parked in `FAILED`.

**Do not delete it.** It is the record of something that actually happened to a
customer's application, and deleting it makes the outbox disagree with the state
that produced it — permanently, and silently.

Instead:

1. Read the payload and establish why it is rejected.
2. Fix the cause: the topic, the permission, the schema.
3. Reset it for one more attempt:

```sql
UPDATE application.outbox_event
   SET status = 'PENDING', attempts = 0, next_attempt_at = now(), last_error_code = NULL
 WHERE id = '<event id>';
```

If it cannot be published at all, escalate rather than deleting. Someone has to
decide what the customer-visible consequence is.

## What to tell people

Applications are still being accepted — submission never depends on the broker
being reachable. What is delayed is assessment. If the stall lasts, applicants
will see their applications sitting in `SUBMITTED` and will call.

## Related

[`kafka-consumer-lag.md`](kafka-consumer-lag.md) ·
[`kafka-under-replicated.md`](kafka-under-replicated.md) ·
[`event-replay.md`](event-replay.md)
