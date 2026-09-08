# ADR-0006: API Gateway REST API rather than HTTP API

**Status:** Accepted
**Date:** 2026-09-05

## Context

HTTP API is the cheaper, faster, simpler default: roughly a third of the price
per million requests and lower latency. It is the right choice for most new APIs,
which is why choosing otherwise needs a reason.

## Decision

**REST API**, regional, for three capabilities HTTP API does not have:

1. **AWS WAF integration.** HTTP API cannot be associated with a Web ACL at all.
   A WAF would have to sit in front of a CloudFront distribution instead, adding
   a component and a caching layer this platform explicitly does not want.
2. **Usage plans and API keys.** How a partner gets its own throttle and quota.
   HTTP API has account-level throttling only, so one partner's burst degrades
   every other partner.
3. **Request validation against a model**, rejecting a malformed request at the
   edge instead of spending a VPC Link connection and a JVM thread on it.

## Consequences

- Rate limiting, managed rule groups and per-partner quotas at the edge.
- **Cost: roughly 3.5× the per-request price**, and added latency.
- **Cost: a more complex resource model** — resources, methods, integrations,
  deployments and stages, where HTTP API needs a route and an integration.
- Regional rather than edge-optimised, so the request path is short and the WAF
  association is regional.
- Request validation is **parameters only** in practice: body validation needs a
  JSON Schema model per method and the routing is a single greedy proxy. The
  shape of a submission is checked by bean validation in the service, where that
  schema already lives. Stated plainly rather than described as full validation.

## Alternatives considered

**HTTP API plus CloudFront plus WAF.** Recovers the WAF at the cost of a caching
layer in front of an API whose responses are per-applicant and
authorisation-dependent. A shared cache there is a cross-tenant leak waiting for
a cache-key mistake.

**An Application Load Balancer with WAF, no API Gateway.** Loses usage plans and
API keys, which is how partner throttling works here.

**Revisit if** request volume grows enough that 3.5× is material, or if partner
throttling moves into the services.
