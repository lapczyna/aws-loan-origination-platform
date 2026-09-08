> **Nothing in this repository has been deployed.** These runbooks describe how
> the platform would be operated. No alarm has ever fired, because no alarm has
> ever existed.

# Runbook: Kafka consumer lag is rising

**Alarm:** `kafka-consumer-lag` (`SumOffsetLag`)

---

## What the alarm means

Events are being published faster than a consumer group is processing them, or a
consumer group has stopped processing altogether.

**Lag is not loss.** The events are on the broker and will be processed when the
consumer catches up. What is affected is latency: an applicant waiting for a
decision waits longer, and the audit trail falls behind the state it describes.

## Which consumer

Lag is per group, and the groups do different things:

| Group | Consumes | Consequence of lag |
|---|---|---|
| `workflow-service.application-events` | Submissions | Assessments do not start |
| `application-service.workflow-events` | Assessment outcomes | Decisions are not recorded; applications sit in `CHECKS_IN_PROGRESS` |
| `application-service.document-events` | Document acceptances | Submission is refused for documents that have in fact been accepted |
| `audit-service.all-events` | Everything | The audit trail lags. **Nothing is lost** — the trail is append-only and catches up. |

Check which one before doing anything: the answer changes both the urgency and
the fix.

## First checks

1. **Is the consumer alive?** A crash-looping pod shows lag rising in a straight
   line with no processing at all.
2. **Is it rebalancing repeatedly?** Look for repeated "Attempt to heartbeat
   failed" or rejoin messages. A consumer whose processing exceeds
   `max.poll.interval.ms` is evicted, rebalances, and is evicted again — lag
   rises the whole time and the pod never looks unhealthy.
3. **One partition or all of them?** A single lagging partition is usually one
   slow or poisoned message; all partitions is capacity or a dependency.
4. **Is a dependency slow?** These consumers write to PostgreSQL. Check
   `rds-saturation` before adding consumers.

## Actions

- **Scale out.** Only up to the partition count: a consumer group cannot use
  more consumers than there are partitions, so the eleventh pod on a
  ten-partition topic does nothing at all.
- **Fix the poisoned message.** A message that throws on every attempt blocks
  its partition. It goes to the dead-letter topic; see
  [`event-replay.md`](event-replay.md).
- **Do not reset offsets to skip the backlog.** Skipping means those events are
  never processed, and for the audit consumer that is a permanent hole in the
  record. If you genuinely must, it is a decision with a name on it.

## What not to do

Do not increase `max.poll.records` to "process faster". It increases the work
per poll, which makes eviction more likely, which makes lag worse.

## Related

[`stuck-outbox.md`](stuck-outbox.md) · [`event-replay.md`](event-replay.md) ·
[`rds-saturation.md`](rds-saturation.md)
