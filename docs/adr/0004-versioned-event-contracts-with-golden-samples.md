# ADR-0004: Versioned event contracts with frozen golden samples

**Status:** Accepted
**Date:** 2026-09-02

## Context

Four services communicate only through events, so the event envelope *is* the
integration contract. The failure mode is well known and quiet: a producer
renames a field, its own tests pass because they were regenerated from the new
code, and a consumer breaks in production against a message the producer's test
suite never produced.

## Decision

A versioned envelope shared as `shared/event-contracts`, with the schema version
carried on every event. Alongside it, **nine frozen golden sample files** — real
serialised envelopes, committed — that a compatibility test deserialises on every
build.

Removing or renaming a required field fails the build against a sample the
current code did not generate.

Serialisation goes through one canonical Jackson mapper: timestamps as ISO-8601
strings rather than numeric, unknown properties ignored so a consumer tolerates a
producer that has added a field.

## Consequences

- A breaking change is caught at compile time by the producer, not at runtime by
  the consumer.
- The samples double as documentation of what the wire format actually looks
  like, which is more reliable than a description of it.
- Consumers tolerate additive changes, so a producer can roll out ahead of them.
- **Cost: the samples must be maintained.** A deliberate breaking change means a
  new version and a new sample, and the temptation is to edit the old one — which
  destroys the guarantee.
- **Cost: no runtime schema enforcement.** A producer that publishes something
  the contract forbids is caught by tests, not by the broker.

## Alternatives considered

**AWS Glue Schema Registry or Confluent Schema Registry with Avro.** Stronger:
enforcement at publish time, not just at build time. Rejected as a component to
operate, a serialisation format that makes debugging harder (a message is no
longer readable with `kafka-console-consumer`), and a hard dependency at
startup. Worth revisiting with more producers or external consumers.

**JSON Schema validated at runtime.** Rejected as validating each message twice —
once against the schema, once by deserialisation — for a failure the golden
samples already catch earlier.

Until a registry exists, this suite **is** the enforcement.
