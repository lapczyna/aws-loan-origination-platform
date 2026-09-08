> **Nothing in this repository has been deployed.** These runbooks describe how
> the platform would be operated. No alarm has ever fired, because no alarm has
> ever existed.

# Runbook: the database is saturated

**Alarms:** `rds-cpu`, `rds-free-storage`, `rds-connections`

---

## Storage first, always

Of the three, **free storage is the one that takes the platform down**. CPU and
connections degrade; storage exhaustion stops the database.

Storage autoscaling is enabled with a ceiling, so this alarm normally means
either the ceiling has been reached or growth is faster than autoscaling can
follow.

```sql
-- What is actually large?
SELECT schemaname, relname, pg_size_pretty(pg_total_relation_size(relid)) AS size
FROM pg_catalog.pg_statio_user_tables
ORDER BY pg_total_relation_size(relid) DESC LIMIT 20;
```

Usual suspects, in order:

1. **A published outbox that is not being pruned.** Published rows are history,
   not work. They should not accumulate forever.
2. **Idempotency keys past their retention.** Their purge job runs on a schedule;
   confirm it is running.
3. **Audit records.** They grow forever *by design* in this schema, which is why
   the durable copy belongs in S3 under Object Lock and the database copy is the
   working one.
4. **A long-running transaction holding dead tuples.** Vacuum cannot reclaim
   space while an old snapshot is open.

**Do not delete audit records to reclaim space.** The runtime role cannot, and
that is the point. Raise the ceiling instead.

## CPU

1. Performance Insights, top SQL by load. This is the difference between "the
   database is slow" and "the database is waiting on this query".
2. Check the slow-query log. It logs statements over the threshold and
   deliberately **not** all statements — full statement logging writes bound
   parameters, which here means applicant names and email addresses in CloudWatch
   Logs.
3. Look for a missing index after a schema change, a sequential scan on a table
   that has grown, or a poller running far more often than intended.

The outbox pollers use `FOR UPDATE SKIP LOCKED` with a bounded batch, so they
should not contend. If they are the top load, check the poll interval — a
misconfigured `PT0.001S` is an easy mistake and looks exactly like this.

## Connections

Each service runs a bounded pool, so the total is predictable. A connection alarm
usually means one of:

| Cause | Sign |
|---|---|
| Too many replicas | Pool size × replicas exceeds `max_connections` |
| Leaked connections | Count rises monotonically and never falls |
| Idle in transaction | Sessions holding locks and blocking vacuum |

```sql
SELECT state, count(*), max(now() - state_change) AS longest
FROM pg_stat_activity GROUP BY state;
```

`idle in transaction` sessions are terminated after 60 seconds by
`idle_in_transaction_session_timeout`, which exists precisely because one leaked
session can stall the whole database. If you see many, something is not
committing.

## Escalation

Scaling the instance is a last resort during an incident: it is a failover, so it
costs an interruption. Multi-AZ makes that ~1–2 minutes rather than several, but
it is still an interruption. Fix the query first if you can.

## Related

[`api-latency.md`](api-latency.md) ·
[`cross-region-replica-promotion.md`](cross-region-replica-promotion.md)
