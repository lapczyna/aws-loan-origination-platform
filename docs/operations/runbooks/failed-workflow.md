> **Nothing in this repository has been deployed.** These runbooks describe how
> the platform would be operated. No alarm has ever fired, because no alarm has
> ever existed.

# Runbook: applications are stuck mid-assessment

**Alarm:** `applications-stuck` (`los.applications.stuck`)

---

## What the alarm means

Applications have been in a non-terminal state — `SUBMITTED`, `VALIDATING`,
`CHECKS_IN_PROGRESS` — for longer than an assessment should take.

Each one is a person waiting for an answer.

## The distinction that matters

A check can end in three ways, and only two of them are about the applicant:

| Ending | Meaning |
|---|---|
| An outcome (`PASSED`, `FAILED`, `INCONCLUSIVE`) | The provider answered. This is about the applicant. |
| Abandoned | The provider could not be reached within its retry budget. **This is not about the applicant.** |
| Still scheduled | Not finished yet. |

An abandoned check records **no outcome**, deliberately, so that "the bureau was
down" can never later be read as "the applicant failed". When you are working
through stuck applications, keep that distinction: an application abandoned by an
outage must not be rejected on the strength of it.

## First checks

```sql
-- What is stuck, in what state, and for how long?
SELECT status, count(*), min(updated_at) AS oldest
FROM application.loan_application
WHERE status IN ('SUBMITTED', 'VALIDATING', 'CHECKS_IN_PROGRESS')
GROUP BY status;

-- Which checks have not completed?
SELECT c.check_type, c.attempts, c.last_error_code, c.next_attempt_at
FROM workflow.workflow_check c
JOIN workflow.workflow_instance w ON w.id = c.workflow_id
WHERE c.outcome IS NULL
ORDER BY c.next_attempt_at;
```

Then work outwards:

1. **Stuck in `SUBMITTED`?** The submission event never reached the workflow
   context. Look at the outbox and consumer lag, not at the workflow.
2. **Stuck in `CHECKS_IN_PROGRESS`?** The assessment started. Look at the checks.
3. **Stuck in `VALIDATING`?** Rare, and usually a workflow poller that is not
   running.

## Likely causes

| Cause | Sign | Action |
|---|---|---|
| Event never delivered | No `workflow_instance` row for the application | [`stuck-outbox.md`](stuck-outbox.md), [`kafka-consumer-lag.md`](kafka-consumer-lag.md) |
| Workflow poller not running | `next_attempt_at` long past, `attempts` not increasing | Restart the workflow service; check for a crash loop |
| Provider outage | Many checks with the same `last_error_code` | Nothing to do per application. Confirm the retry schedule is advancing. |
| Circuit breaker open | Failures stop, then resume after the wait duration | Working as intended. Confirm it closes. |
| Leases not expiring | `leased_until` in the past but rows not reclaimed | A clock problem, or a poller stopped mid-lease. Restart. |
| Outcome never applied | Workflow `COMPLETED` but the application is not | The workflow event was not consumed. Check the application service's consumer lag. |

## Actions

- **Do not manually set an application to `APPROVED` or `REJECTED`.** The state
  machine refuses illegal transitions for a reason, the audit trail records what
  actually happened, and a decision written directly into the database is a
  lending decision with no evidence behind it.
- Fixing the cause is enough. The workflow is durable: it resumes from where it
  stopped once the poller runs, with no replay needed.
- If a provider will be down for a long time, the honest option is to route the
  affected applications to manual review so a human can decide, rather than
  leaving applicants waiting indefinitely.

## Related

[`stuck-outbox.md`](stuck-outbox.md) ·
[`manual-review-backlog.md`](manual-review-backlog.md) ·
[`event-replay.md`](event-replay.md)
