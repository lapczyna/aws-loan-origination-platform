# ADR-0014: The end-to-end suite runs the services as separate processes

**Status:** Accepted
**Date:** 2026-09-07

## Context

The end-to-end suite must exercise the whole platform: four services, real HTTP,
real Kafka, real S3, real migrations. Three shapes were possible — drive the built
images through Docker Compose, run four Spring contexts in one JVM, or launch the
real executable jars as separate processes.

The plan called for four Spring contexts in one JVM, as the fast option.

**It does not work**, for two reasons that are cheap to state and expensive to
rediscover:

1. **Every service jar contains `/application.yaml`.** On a shared classpath,
   Spring resolves that to whichever jar the class loader reaches first, so three
   of the four services would silently boot with another service's configuration.
2. **Every service jar contains `db/migration/V1__*.sql`.** Flyway scans the
   classpath, finds four different migrations all claiming version 1, and refuses
   to start.

## Decision

Launch the four **real executable jars as four operating-system processes**,
wired to shared PostgreSQL, Kafka and LocalStack containers. Readiness is awaited
on each service's own readiness probe. Output goes to a log file per service, and
the tests assert against those files as well as against the API and the database.

## Consequences

- Each process sees only its own configuration and its own migrations, because
  that is all that is on its classpath. **The tested configuration is the
  deployed configuration**, which is the property the suite exists to check.
- Nothing passes between services as a Java object, so the suite cannot hide a
  serialisation change only one side knows about.
- The tests can assert on the log files, which is how "no personal data reached
  any service's logs" is checked across a boundary — something no single
  service's own tests can do.
- **Cost: `mvn package` must run first.** The suite runs the jars, not the
  classes, and says so when they are missing.
- **Cost: slower.** Four JVMs start per run.
- **Cost: process lifecycle to manage** — ports, shutdown, orphans on a killed
  build. Handled with a shutdown hook and reserved ports.

## Alternatives considered

**Four Spring contexts in one JVM.** The reason for this ADR. The workarounds —
per-service config locations, per-service migration paths, a class loader per
service — all make the tested configuration differ from the deployed one.

**Docker Compose with the built images.** The most faithful, and slow enough that
the suite would stop being run, with failures that are hard to attribute. The
Compose stack is exercised separately by `scripts/local-smoke-test.sh`, so
neither path is unverified.
