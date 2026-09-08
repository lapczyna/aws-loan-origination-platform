> **Nothing in this repository has been deployed.** These runbooks describe how
> the platform would be operated. No alarm has ever fired, because no alarm has
> ever existed.

# Runbook: the manual review queue is backing up

**Alarm:** `manual-review-backlog` (`los.manual.review.pending`)

---

## What the alarm means

Applications waiting for a human decision have exceeded the threshold.

**This is usually not a technical fault**, and that is what makes it easy to
mishandle. It is either a staffing problem or a signal that something upstream
has started routing far more applications to humans than it should.

## The question to answer first

**Is the rate normal and the capacity short, or has the rate changed?**

```sql
-- How fast are applications arriving in the queue, by day?
SELECT date_trunc('day', updated_at) AS day, count(*)
FROM application.loan_application
WHERE status = 'MANUAL_REVIEW'
GROUP BY 1 ORDER BY 1 DESC LIMIT 14;

-- And why are they there?
SELECT decision_reason_code, count(*)
FROM application.loan_application
WHERE status = 'MANUAL_REVIEW'
GROUP BY 1 ORDER BY 2 DESC;
```

A sudden change in the reason-code mix is the tell.

| Dominant reason | What it means |
|---|---|
| `CREDIT_SCORE_IN_REVIEW_BAND` | Normal. Applicants legitimately falling between the automatic thresholds. |
| `CHECK_INCONCLUSIVE` | A provider is answering "cannot decide" far more than usual. **Investigate the provider.** |
| `CREDIT_SCORE_UNAVAILABLE` | The bureau adapter is returning a passed check with no score. That is a contract violation and a bug. |
| A provider outage | Abandoned checks routed to humans. The outage is the incident; the queue is a symptom. |

## Actions

- **Rate normal, capacity short:** a staffing decision, not an engineering one.
  Escalate to whoever owns the review team.
- **Rate changed:** find the upstream cause. Adding reviewers to a queue being
  filled by a broken provider treats the symptom while the cause keeps working.
- **A provider is degraded:** fix or disable it. Routing everything to humans is
  the correct fallback, but only while somebody is fixing the provider.

## What not to do

**Do not raise the automatic approval thresholds to shorten the queue.** That is
not a queue-management change, it is a change to the platform's lending risk
appetite, made under time pressure by whoever happened to be on call. Threshold
changes are a product decision with an owner, a review and a record.

Equally: do not approve applications in bulk to clear the backlog. Every decision
carries the deciding reviewer's identity into the audit trail, and it will be
their name against it.

## Related

[`failed-workflow.md`](failed-workflow.md)
