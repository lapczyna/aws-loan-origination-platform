# ADR-0002: RDS for PostgreSQL rather than Aurora

**Status:** Accepted
**Date:** 2026-09-01

## Context

The platform needs a relational database with strong transactional guarantees.
The outbox pattern depends on `FOR UPDATE SKIP LOCKED`, the workflow depends on
lease-based claiming, and the audit trail depends on constraints being enforced.
All of that is ordinary PostgreSQL.

Aurora PostgreSQL is the default recommendation in most AWS reference
architectures, which is reason enough to examine it rather than adopt it.

## Decision

**Amazon RDS for PostgreSQL**, Multi-AZ in production, with an optional
cross-region read replica for disaster recovery that is disabled by default.

## Consequences

- Cheaper at this scale, and the pricing model is easier to predict: Aurora bills
  I/O separately, and a chatty poller makes that unpleasant to forecast.
- Simpler to reason about. One writer, one synchronous standby.
- **Portable.** It is ordinary PostgreSQL, so moving off AWS is a migration
  rather than a rewrite. Aurora's storage layer is not reproducible elsewhere.
- **Cost: no Aurora fast cloning**, which is genuinely useful for spinning up
  realistic test environments.
- **Cost: failover is slower.** Aurora typically fails over faster than RDS
  Multi-AZ's one to two minutes.
- **Cost: read scaling is harder.** Aurora replicas share storage and lag less.
  This platform's reads are small and mostly by primary key, so it does not bite
  yet.

## Alternatives considered

**Aurora PostgreSQL.** The right answer at high write throughput, where fast
cloning matters, or where read replicas carry real load. None applies here, and
adopting it anyway would be cargo-culting the reference architecture.

**Aurora Serverless v2.** Attractive for a bursty workload. Rejected because this
workload is not bursty in the way that pays off, and its scaling behaviour adds a
variable to debug during an incident.

**DynamoDB.** Rejected outright. The outbox, the lease-based workflow claiming
and the audit chain all depend on multi-row transactions and expressive
predicates.
