# ADR-0001: Hexagonal architecture with four bounded contexts

**Status:** Accepted
**Date:** 2026-09-01

## Context

A loan origination platform has several concerns that change at different rates
and for different reasons: the application itself, the assessment workflow,
supporting documents, and the audit record. Putting them in one deployable makes
every change a change to everything; splitting them badly produces a distributed
monolith with all the coupling and none of the convenience.

Within each service, the risk is the usual one: business rules that quietly
acquire a dependency on the framework, the ORM or the serialiser, at which point
they can no longer be reasoned about or tested without booting infrastructure.

## Decision

Four services, one per bounded context: **application**, **workflow**,
**document**, **audit**. Each owns its own database schema and communicates with
the others only through published events.

Inside each service, a hexagonal structure:

```
domain/     pure Java. No Spring, no JPA, no Jackson.
usecase/    application services and outbound port interfaces
adapter/    in/{web,messaging}   out/{persistence,messaging,s3,external}
config/     wiring
```

**Enforced by 31 ArchUnit rules, not by discipline.** They fail the build.

## Consequences

- The domain is testable without a container, and most of the test suite runs in
  milliseconds.
- Swapping an adapter — a simulated check for a real bureau — does not touch the
  domain.
- **Cost: more indirection.** A field added to an API response can touch a
  request DTO, a domain object, an entity and a response DTO. This is the price,
  and on a small feature it feels like pure overhead.
- **Cost: eventual consistency between contexts.** The application context holds
  a projection of document status. It can only delay a submission, never wrongly
  permit one, but it does mean a submission may be refused for a document that
  has in fact just been accepted.
- The ArchUnit rules found four genuine design leaks the first time they ran, so
  they were earning their place before they were finished.

## Alternatives considered

**A modular monolith.** Genuinely simpler, and defensible for this scale. Not
chosen because the point of the exercise is to demonstrate service boundaries and
event-driven integration — an honest reason, and worth stating rather than
inventing a technical one.

**Anaemic domain with service-layer logic.** Rejected: state transitions
scattered across services is how an application ends up in a state nobody
intended, with no single place to look.
