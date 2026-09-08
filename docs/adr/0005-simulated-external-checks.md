# ADR-0005: External checks are simulations behind ports

**Status:** Accepted
**Date:** 2026-09-03

## Context

Assessment needs KYC, AML, fraud and credit-scoring providers. Real ones need
commercial agreements, credentials, and the transmission of applicant personal
data to third parties. None of that is available to a reference implementation,
and none of it should be faked.

The risk is presenting a simulation as an integration. Someone reads the
repository, sees `KycPort` with an adapter, and concludes the platform integrates
with a bureau. It does not.

## Decision

Four ports — `KycPort`, `AmlPort`, `FraudPort`, `CreditScoringPort` — with
**simulated adapters, labelled as simulations everywhere they appear**: in the
class name, in the Javadoc, in a startup log line, in the README, and here.

The simulators are written against the real port contract and cover every
behaviour a real provider exhibits: success, business rejection, inconclusive
answer, timeout, transient failure that recovers, permanent failure, and a slow
answer near the timeout boundary.

Which behaviour is produced is chosen **deterministically** from the product
code, falling back to the applicant reference.

## Consequences

- Every failure path is reachable from a test and from the local Compose stack,
  so the retry, circuit-breaker and dead-letter paths are exercised rather than
  merely written.
- Deterministic rather than random: a simulator that rolled a die gives a suite
  failing one run in twenty for no reproducible reason.
- Replacing a simulator with a real adapter is a class, not a redesign — which is
  the actual claim being made about the architecture.
- **Cost: nothing here proves a real integration works.** A real bureau brings
  authentication, response-integrity, rate-limit and privacy concerns this does
  not model.
- **Cost: the product code carries test-shaped meaning.** It is why the scenario
  is selectable at all — the applicant reference reaching the workflow context is
  an HMAC pseudonym and can never contain a marker — but a real deployment would
  drop that resolution entirely.

## Alternatives considered

**WireMock stubs of real bureau APIs.** More realistic transport, but stubs a
protocol nobody can verify without the real specification — realistic-looking and
unverifiable, which is worse than an honest simulation.

**Leaving the ports unimplemented.** Then nothing runs end to end, and the
workflow's most interesting behaviour is untested.
