# ADR-0009: A hash-chained, append-only audit trail

**Status:** Accepted
**Date:** 2026-09-04

## Context

A lending platform must be able to show what it did and when. The hard case is
not an outage — it is someone with legitimate database access altering a decision
after the fact. An audit table that can be updated proves nothing, because the
evidence and the thing it is evidence about are under the same control.

## Decision

An append-only trail with a **hash chain**. Each record's SHA-256 covers its
canonical fields **and the previous record's hash**, so altering any record
invalidates every record after it.

Enforced at two independent levels:

- **The database refuses it.** The runtime role is granted `SELECT, INSERT` on
  `audit.audit_record` and nothing else. Verified against a real PostgreSQL:
  `UPDATE` and `DELETE` are rejected.
- **The chain detects it.** Editing and deleting rows *as a superuser* was
  performed against a real database, and verification caught both.

Canonicalisation is explicit: fields joined with `U+001F`, summary keys sorted,
so the same logical record always hashes identically.

Summaries are governed by an **allow-list** of 17 code-like keys with a
128-character value limit. A rejected key is named; its value never is.

## Consequences

- Tampering is detectable, and the detection is cheap: verify the chain.
- An allow-list rather than a deny-list, because a deny-list fails open on
  anything nobody anticipated — and "anything nobody anticipated" is precisely
  where personal data leaks in.
- The trail holds pseudonyms and code-like values only. No names, no email
  addresses, no free text.
- **Cost: it is detection, not prevention.** A sufficiently privileged user can
  still alter a row. What they cannot do is make the chain agree. Preventing a
  DBA from writing to a database they administer is not achievable; making the
  attempt evident is.
- **Cost: no deletion, ever.** This collides with erasure requests, and the
  collision is real rather than solved. See
  [`../security/data-classification.md`](../security/data-classification.md).
- **Cost: repairing a broken chain destroys the evidence.** The runbook says not
  to.

## Alternatives considered

**A plain audit table with timestamps.** Proves nothing against an insider.

**QLDB.** Purpose-built and a genuinely good fit, but AWS has placed it on a
deprecation path, and building on that is a poor decision to demonstrate.

**Writing only to S3 with Object Lock.** Strong immutability, poor queryability.
Both are used: the database copy is the working one, the S3 copy is the one that
survives someone with database access.
