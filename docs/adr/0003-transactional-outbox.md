# ADR-0003: A transactional outbox rather than publishing directly

**Status:** Accepted
**Date:** 2026-09-02

## Context

A submission has to do two things: change the application's state in PostgreSQL,
and tell the rest of the platform. Doing both directly is a **dual write**, and
dual writes fail in one of two ways, always:

- Commit the database, then fail to publish. The application is submitted and
  nothing assesses it. Silent, and discovered by the applicant.
- Publish, then fail to commit. Downstream assesses an application that does not
  exist.

There is no ordering of two independent systems that avoids this.

## Decision

Write the event to an `outbox_event` table **in the same transaction** as the
state change. A background publisher claims batches with `FOR UPDATE SKIP
LOCKED`, publishes to Kafka, and marks rows published only after the broker
acknowledges.

Failures back off exponentially with full jitter. A row that exhausts its budget
is parked in `FAILED` rather than retried forever.

## Consequences

- The state change and the intent to publish are atomic. Neither can exist
  without the other.
- **An unavailable broker is not an unavailable API.** Submission keeps working;
  events accumulate durably and drain on recovery. This is covered end to end by
  pausing the broker mid-submission.
- Delivery is **at-least-once**, so every consumer must be idempotent. Each keeps
  a de-duplication ledger keyed on event id.
- **Cost: latency.** An event is published on the next poll, not instantly.
- **Cost: a table that grows** and needs pruning; published rows are history, not
  work.
- **Cost: an operational surface.** Two alarms — oldest age and backlog — and a
  runbook, because "backlog with rising age" and "backlog with flat age" are
  different incidents.

## Alternatives considered

**Publish directly, accept the risk.** The failure is silent and affects
individual customers rather than being visible in aggregate. Not acceptable for
lending decisions.

**Change data capture (Debezium).** Genuinely good, and removes the poller. Costs
a Kafka Connect cluster to operate and couples the event schema to the table
schema unless carefully mediated. Too much operational surface for this platform.

**Kafka transactions across the database.** Not a thing. Kafka's transactions are
within Kafka.
