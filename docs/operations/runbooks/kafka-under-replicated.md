> **Nothing in this repository has been deployed.** These runbooks describe how
> the platform would be operated. No alarm has ever fired, because no alarm has
> ever existed.

# Runbook: under-replicated partitions

**Alarm:** `kafka-under-replicated` (`UnderReplicatedPartitions` > 0)

---

## What the alarm means

At least one partition has fewer in-sync replicas than its replication factor. A
broker is down, unreachable, or too far behind to be considered in sync.

**This is a durability alarm, not a latency one.** The cluster is still serving,
and that is exactly why it is easy to ignore. The margin protecting against data
loss has shrunk.

## Why the configuration makes this safe, and what it costs

The cluster runs with replication factor 3, `min.insync.replicas=2` and
`unclean.leader.election.enable=false`. Together:

- **Losing one broker is survivable.** Two replicas remain in sync, which
  satisfies the minimum, and producers using `acks=all` keep working.
- **Losing two brokers stops writes.** The minimum can no longer be met and
  producers receive `NOT_ENOUGH_REPLICAS`. That is deliberate: the alternative is
  accepting a write that only one broker has, and then losing it.
- **A partition with no in-sync replica stays offline.** Unclean leader election
  is off, so an out-of-sync replica is never promoted. It would come back as
  leader missing records other replicas had already acknowledged — silent data
  loss, presented as a recovery.

So: one broker down is a real alarm with no customer impact. Two is an outage.

## First checks

1. **How many brokers are healthy?** MSK console, or `kafka-topics --describe`.
2. **Is it recovering?** A broker restarting after maintenance catches up on its
   own; the count falls back to zero within minutes.
3. **Is it one partition or many?** Many suggests a broker; one suggests a
   specific replica that is behind.
4. **Is a producer failing with `NOT_ENOUGH_REPLICAS`?** That means the minimum
   is already unmet. The outbox holds the events — see
   [`stuck-outbox.md`](stuck-outbox.md) — but the platform has stopped
   publishing.

## Actions

- **One broker, recovering:** watch it. Confirm the count returns to zero.
- **One broker, not recovering:** MSK replaces a failed broker automatically.
  If it has not, raise it with AWS. Do not delete the broker.
- **Two or more brokers:** an outage. The outbox is holding events durably;
  publication resumes on recovery, so the priority is restoring the cluster, not
  working around it.
- **Storage exhaustion:** a broker that has filled its volume stops replicating.
  Check EBS usage; storage autoscaling exists for this and has a ceiling.

## What not to do

**Never enable unclean leader election to clear this alarm.** It converts a
visible, correct refusal to serve into invisible data loss. If somebody suggests
it during an incident, this paragraph is why the answer is no.

## Related

[`stuck-outbox.md`](stuck-outbox.md) · [`kafka-consumer-lag.md`](kafka-consumer-lag.md)
