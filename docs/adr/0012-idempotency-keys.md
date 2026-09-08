# ADR-0012: Idempotency keys with a canonical request fingerprint

**Status:** Accepted
**Date:** 2026-09-02

## Context

A client that times out on `POST /submit` does not know whether the submission
happened. It will retry. Without a mechanism, retrying submits twice — which for
a loan application means two assessments and potentially two decisions about one
person.

## Decision

An `Idempotency-Key` header on creation and submission. The service stores the
key with a **SHA-256 fingerprint of the canonicalised request body**:

| Situation | Result |
|---|---|
| Same key, same request | The original response, with `Idempotent-Replay: true` |
| Same key, different request | `409 IDEMPOTENCY_KEY_REUSED` |
| Same key, original in flight | `409 IDEMPOTENCY_KEY_IN_USE` |

**Canonicalised** is the load-bearing word: a client retrying through a different
HTTP library serialises its JSON with different whitespace and key order, and
that must not read as a different request.

Submission has no body, so the **path identifier** is what is fingerprinted —
reusing a key against a different application is the only way one key can mean two
things.

## Consequences

- A retry is safe. The decisive property is not the status code but the effect:
  exactly one submission event, so exactly one assessment.
- Concurrent requests with one key are safe. A unique constraint resolves the
  race; the losers get a conflict rather than a constraint violation escaping to
  the client.
- **Returning the first application's response for a reused key would be the most
  dangerous possible answer** — it tells a client its second application was
  submitted when it was not. Hence 409.
- **Cost: clients must generate and reuse keys**, and a client that generates a
  fresh key per attempt gets no protection.
- **Cost: a table that grows**, purged on a schedule. The retention window is how
  long a retry can arrive and still be recognised.

## Alternatives considered

**Deduplicate on request content alone.** Cannot distinguish a retry from a
legitimate second identical request — two applications for the same amount by the
same applicant are not necessarily a mistake.

**Idempotency at the gateway.** API Gateway has no such feature, and it would
need the same storage anyway.

**`PUT` with a client-generated id.** Cleaner in REST terms and a reasonable
alternative. Rejected because it moves identifier generation to the client, and
the platform wants opaque server-generated identifiers.
