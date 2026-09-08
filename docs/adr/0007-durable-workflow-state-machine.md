# ADR-0007: A durable workflow state machine, not a synchronous chain

**Status:** Accepted
**Date:** 2026-09-02

## Context

Assessment runs four external checks and reaches a decision. The obvious
implementation is a service method that calls each in turn and returns the
result.

That implementation loses everything if the process dies. It holds a request
thread for the duration of the slowest provider. And its retries live in memory,
so a restart forgets what had been retried and what had not.

## Decision

A **state machine persisted in PostgreSQL**. Each workflow instance and each
check is a row. A scheduled poller claims due work with a lease:

```sql
UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING id
```

Retries live in the **schedule** — a `next_attempt_at` column — not in a
`for` loop. Resilience4j supplies a circuit breaker, a bounded thread-pool
bulkhead and an explicit timeout per check type for the synchronous call itself.

A check whose retry budget is exhausted is **abandoned, recording no outcome**.

## Consequences

- A restart loses nothing. The workflow resumes from where it stopped.
- No request thread is held. Submission returns 202 immediately.
- Multiple replicas cooperate safely: `SKIP LOCKED` means two pollers take
  disjoint work without coordination.
- **"The bureau was down" can never be read as "the applicant failed."** An
  abandoned check records no outcome, so nothing downstream can mistake an
  outage for a decision about a person. This is the single most important
  property here.
- **Cost: latency.** Work advances on a poll, not instantly.
- **Cost: more moving parts** — leases, expiry, backoff — and a lease that never
  expires is a workflow that never runs again.
- **Cost: the database is now a queue**, which it is adequate at, not excellent
  at. Fine at this volume.

## Alternatives considered

**AWS Step Functions.** A genuinely strong fit: durable, visual, managed. Not
chosen because the orchestration logic would move out of the codebase into
infrastructure, which makes it harder to test locally and to review as code. A
defensible decision to make the other way.

**A saga library (Axon, Eventuate).** More machinery than four checks justify.

**Synchronous chain with in-memory retries.** The failure mode above.
