# ADR-0010: An HMAC pseudonym for the applicant, peppered from a file

**Status:** Accepted
**Date:** 2026-09-01

## Context

Four contexts need to refer to the same applicant. Passing name and date of birth
between them spreads personal data across four schemas, four sets of logs and
every event on the bus — and each copy is a place it can leak.

## Decision

`ApplicantReference` is `HMAC-SHA-256(pepper, canonical applicant identity)`,
truncated, prefixed `APR-`. Only the reference crosses a context boundary.

Two details that are the whole decision:

**Length-prefixed canonicalisation.** Fields are joined as `len:value` so two
different applicants cannot collide by shifting a character across a field
boundary.

**The pepper is read from a FILE**, never an environment variable. Environment
variables appear in `/proc`, in crash dumps, in `docker inspect`, and in the
output of anything that dumps a process's environment for debugging. In
Kubernetes the pepper arrives as a file through the Secrets Store CSI driver, and
the local stack reads a file too — so the code path under test is the one that
runs.

## Consequences

- The workflow, document and audit contexts hold **no** applicant personal data.
- The reference is stable, so the same applicant is recognisable across
  applications without storing anything identifying.
- HMAC rather than a plain hash: without the pepper, an attacker who guesses an
  identity cannot confirm it by computing the digest.
- **Cost: it is pseudonymised, NOT anonymised.** The same applicant always
  produces the same reference, so it is linkable and remains personal data under
  GDPR. Treating it as anonymous would be the comfortable reading and the wrong
  one.
- **Cost: the pepper cannot be rotated casually.** It is an input to a value
  stored in every table and every audit record. Rotation is a data migration with
  a versioned pepper, and the audit trail is never rewritten. See
  [`../operations/runbooks/secret-rotation.md`](../operations/runbooks/secret-rotation.md).
- **Cost: pepper disclosure creates a confirmation oracle** — not decryption, but
  enough to confirm a guessed identity, which matters most for a small population.

## Alternatives considered

**A random opaque id in a mapping table.** Rotatable and unlinkable, but needs a
lookup service every context depends on, and that service becomes the single
place where re-identification is possible.

**Encrypt and pass the ciphertext.** Reversible by design, which defeats the
purpose: the point is that downstream contexts *cannot* recover the identity.

**Plain SHA-256, no pepper.** A dictionary of plausible identities recovers the
input.
