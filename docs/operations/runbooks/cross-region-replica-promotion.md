> **Nothing in this repository has been deployed.** These runbooks describe how
> the platform would be operated. No alarm has ever fired, because no alarm has
> ever existed.

# Runbook: promoting the cross-region replica

**This is a disaster-recovery action of last resort.** Read all of it before
running anything.

---

## Understand what you are about to do

| | Multi-AZ standby (HA) | Cross-region replica (DR) |
|---|---|---|
| Replication | **Synchronous** | **Asynchronous** |
| Data loss | None | **Bounded by replica lag** |
| Failover | Automatic, ~1–2 min | Manual, and one-way |
| Region | Same | Another |

**Promotion is irreversible.** A promoted replica cannot become a replica again.
The original relationship has to be rebuilt from scratch, which means a full
copy.

**You will lose data.** Whatever had not replicated when the primary was lost is
gone. Establish how much *before* promoting — that number is what the business
needs in order to decide, and it is unobtainable afterwards.

**The replica is not a backup.** It replicates a mistaken `DELETE` as faithfully
as a legitimate one. If the incident is data corruption rather than regional
failure, promotion propagates the corruption. Use point-in-time recovery instead.

## Decide first

Promote only if **all** of these hold:

- [ ] The primary region is genuinely unavailable, not merely degraded
- [ ] AWS has confirmed a regional event, or you have independent evidence
- [ ] Someone with authority has accepted the data loss, with a number attached
- [ ] The application can actually be repointed — the DR region has an
      environment able to run it
- [ ] The incident is **not** data corruption

If any is unchecked, stop. Waiting for a region to recover is frequently the
better outcome, and it is always reversible.

## Measure the loss before promoting

```
CloudWatch → RDS → ReplicaLag, at the moment the primary was lost.
```

Lag is in seconds. Translate it into transactions using the recent write rate,
and translate that into applications: "roughly N submissions in the last M
minutes may be lost". That sentence is what a business decision can be made on.

## Promoting

```bash
# 1. Confirm the primary really is gone. Promoting while it lives gives you two
#    writable databases and a split brain you will be reconciling for weeks.
aws rds describe-db-instances --db-instance-identifier <primary> --region <primary-region>

# 2. Promote. One way.
aws rds promote-read-replica --db-instance-identifier <replica> --region <dr-region>

# 3. Wait for available.
aws rds wait db-instance-available --db-instance-identifier <replica> --region <dr-region>
```

Then:

1. **Repoint the application.** The endpoint has changed. Update the secret or
   parameter the services resolve, and restart them.
2. **Confirm the schema version.** Flyway history should match the application
   being deployed. A replica promoted mid-migration may be behind.
3. **Re-enable backups on the promoted instance.** Confirm, do not assume.
4. **Check the audit chain.** The last records before the cutover may be
   incomplete. A chain break here is expected and must be recorded as such, so
   nobody later reads it as tampering.

## Afterwards

- The DR region is now production. It needs the monitoring, alarms and backups
  the primary had.
- There is **no replica any more**. Until a new one is built, there is no DR.
- Reconcile: applications submitted in the lost window need identifying and
  contacting. They are the ones the applicants will call about.

## Related

[`rds-saturation.md`](rds-saturation.md) ·
[`../database-bootstrap.md`](../database-bootstrap.md)
