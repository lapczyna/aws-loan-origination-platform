# Implementation progress

This file is the honest record of what exists, what was actually run, and what
does not exist yet. Anything described as verified was executed locally and its
output observed. Nothing here is aspirational.

**No AWS resource has been created. The repository is private and has not been
pushed to any remote.**

Last updated: 2026-09-06

---

## Environment this was built and verified on

| Tool | Version |
|---|---|
| JDK | Liberica 25.0.2 (LTS) |
| Maven | 3.9.16 via the committed wrapper (script-only, no jar) |
| Docker | 27.0.3, Compose v2.28.1 |
| Terraform | 1.15.6 |
| Helm | 4.2.4 |
| kubectl | 1.29.2 (client only; never connected to a cluster) |
| gitleaks / kubeconform / tflint | 8.30.1 / 0.8.0 / 0.64.0, downloaded to a scratch directory outside the repository |

---

## Phase status

| # | Phase | Status |
|---|---|---|
| 0 | Environment gate | Done |
| 1 | Repository baseline and parent build | Done |
| 2 | Domain model and Application Service core | Done |
| 3 | PostgreSQL migrations, idempotency, outbox, REST API | Done |
| 4 | Kafka contracts and Workflow Service | Contracts done; workflow service not started |
| 5 | Document Service and presigned S3 upload | Not started |
| 6 | Audit Service | Not started |
| 7 | Observability and safe logging | Partially done (see below) |
| 8 | Docker and Docker Compose | Not started |
| 9 | Kubernetes and Helm | Not started |
| 10 | Terraform | Not started |
| 11 | CI/CD and security scanning | Not started |
| 12 | End-to-end verification and public-release review | Not started |

---

## Completed work

### Phase 0 — Environment gate

- Docker Desktop started and the daemon confirmed responding
  (`docker info` → `27.0.3 | linux`).
- `gitleaks`, `kubeconform` and `tflint` downloaded as pinned release binaries
  into the session scratch directory. **They are not in the repository.**
- AWS credentials exist on this machine under `~/.aws` and were deliberately not
  used. No AWS profile or region was exported to any command.

### Phase 1 — Repository baseline

- Maven Wrapper 3.3.4 in `distributionType=only-script` mode, so no
  `maven-wrapper.jar` binary is committed.
- Parent POM on Spring Boot 4.1.1 / Java 25 with Enforcer, Spotless, JaCoCo,
  Failsafe, and opt-in `analysis` and `sbom` profiles.
- `.gitignore`, `.gitattributes`, `.editorconfig`, `.dockerignore`,
  `.env.example`, `.gitleaks.toml`, `Makefile`, `LICENSE`.

Two compatibility problems were found and fixed here rather than at runtime:

1. `resilience4j-bom:2.4.0` does not list the Spring Boot 4 starter, so
   `resilience4j-spring-boot4` is version-managed explicitly in the parent POM.
2. Spring Framework 7 removed `spring-jcl` and now depends on the real
   `commons-logging` artifact, so the Enforcer rule banning it had to be removed
   — it would have banned Spring itself.

### Phase 2 — Domain model

`services/application-service` domain layer, with no Spring, JPA or Jackson
imports anywhere in `domain/`:

- `LoanApplication` aggregate: no setters; every state change is a named
  business operation that checks its own preconditions and records a domain
  event. The business version increments once per operation and is carried on
  every event so consumers can detect out-of-order delivery.
- `ApplicationStatus` state machine with the legal transitions declared once in
  an exhaustive `switch`.
- `ApplicantDetails` is the only type holding personal data. It is deliberately a
  class and not a record, because a record's generated `toString()` would print a
  name, an email address and a date of birth into any log statement that touched
  it. Its `toString()` returns `ApplicantDetails[redacted]`.
- `ApplicantReference` — an irreversible HMAC-SHA-256 pseudonym under a secret
  pepper. The only applicant identifier that crosses a context boundary.
- Value objects: `Money` (integer minor units, never floating point), `LoanTerm`,
  `LoanRequest`, `Decision`, `ApplicationId` (RFC 9562 version 7 UUID, so
  identifiers are unguessable but still index-friendly).
- `ProductRules` — cheap local eligibility rules evaluated before any external
  check is requested.

### Phase 3 — Persistence, idempotency, outbox and the REST API

- Flyway migrations for the `application` schema: the aggregate, an append-only
  version history, the idempotency ledger, the transactional outbox, the
  de-duplication ledger, and an event-fed projection of document status.
- Separate migration and runtime database roles. The runtime role's grants are
  applied by a migration that no-ops when the role has not been bootstrapped, so
  a developer machine still works while production stays least-privilege.
- Hexagonal persistence: the aggregate and its JPA entity are separate types with
  an explicit mapper, so no persistence annotation reaches the domain and no
  entity reaches the API.
- Transactional outbox with a `FOR UPDATE SKIP LOCKED` publisher, exponential
  backoff with full jitter, and a terminal `FAILED` state that parks an event for
  the replay runbook rather than dropping it.
- Idempotent create and submit, keyed on `Idempotency-Key` plus a SHA-256
  fingerprint of the canonicalised request body.
- `/v1` REST API with RFC 9457 Problem Details, OAuth2 resource-server security
  including audience validation, restrictive CORS defaults, and correlation
  identifiers propagated from HTTP through to events.

Four Spring Boot 4 behaviours were found the hard way and are worth recording:

1. **Auto-configuration moved into per-technology modules.** Depending on
   `flyway-core` or `spring-kafka` alone puts the library on the classpath with no
   auto-configuration at all — migrations silently never run, and there is no
   `KafkaTemplate` bean. The fixes are `spring-boot-flyway` and `spring-boot-kafka`.
2. **A record cannot be a JPA `@IdClass`.** The specification requires a public
   class with a public no-argument constructor.
3. **`CHAR(n)` columns fail Hibernate schema validation**, because PostgreSQL
   reports them as `bpchar` while a `String` maps to `varchar`. `VARCHAR` is the
   better choice anyway: `CHAR` blank-pads and compares with the padding.
4. **The PostgreSQL JDBC driver cannot infer a SQL type for `java.time.Instant`**
   bound to a native query. Entity mappings and JPQL handle it; native queries
   need `OffsetDateTime`.

### Phase 4 (partial) — Event contracts

`shared/event-contracts` with the versioned envelope, nine payload records across
three bounded contexts, the shared vocabulary, and `EventJson` — the single
canonical Jackson 3 mapping used by every producer and consumer.

The compatibility suite replays nine frozen golden samples and asserts that each
still deserialises, that a round trip loses no field, that a field added by a
newer producer does not break an older consumer, that timestamps are ISO-8601
strings, and that no payload declares a field whose name suggests personal data.

### Phase 7 (partial) — Observability and safe logging

- Structured JSON logging through Spring Boot 4's built-in support, so no
  third-party encoder is needed and the container writes only to stdout.
- Correlation identifiers: client-supplied values are validated against a
  conservative pattern before they reach the MDC, so a caller cannot inject
  newlines to forge log entries or park a stolen credential in the logs.
- **No generic request/response logging filter anywhere.** Domain logging is
  explicit and names the safe fields it emits.
- Outbox gauges for backlog size, age of the oldest unpublished event, and the
  permanently failed count.
- `CapturedLogs` and `SensitiveMarkers` in `shared/test-support` give tests the
  ability to assert that known synthetic sensitive values never reach the logging
  pipeline. `ApplicationApiIT` uses them.

Still missing from this phase: OpenTelemetry export wiring verified end to end,
the CloudWatch dashboards and alarms (Terraform, phase 10), and business metrics
beyond the outbox gauges.

---

## Verified commands

Each of these was run in this session and its output observed.

```bash
./mvnw -v                                    # Maven 3.9.16, JDK 25.0.2
./mvnw -B verify -Pfast                      # unit tests only, no Docker needed
./mvnw -B verify -pl shared/event-contracts,shared/test-support,services/application-service -am
                                             # full suite including Testcontainers
gitleaks detect --no-git --source . --config .gitleaks.toml   # clean
```

Test totals at this point: **171 passing, 0 failing, 0 skipped.**

| Suite | Tests | Result |
|---|---:|---|
| Event contract compatibility | 23 | pass |
| Application domain (unit) | 122 | pass |
| `OutboxGuaranteesIT` (real PostgreSQL + Kafka) | 9 | pass |
| `ApplicationApiIT` (HTTP, security, privacy) | 17 | pass |

`OutboxGuaranteesIT` proves, against real containers: an event is written in the
same transaction as the state change; a rolled-back transaction leaves no event;
the publisher marks a row published only after the broker acknowledges; a broker
outage leaves events pending and publication resumes on recovery; an exhausted
retry budget parks the event rather than dropping it; two concurrent publishers
take disjoint batches; two concurrent submissions produce exactly one transition;
every event carries a correlation identifier and a causal chain; and the
partition key is always the application identifier.

`ApplicationApiIT` proves: a repeated idempotency key with the same body replays
the original result; the same key with a different body is a 409; formatting
differences are not a conflict; four concurrent requests with one key create one
application; submission is idempotent; validation errors name the field but never
echo the value; error responses carry no stack trace, SQL or table name; expired
tokens and tokens minted for another audience are refused; a partner client
cannot reach the manual-review queue; and applicant personal data is neither
returned by the API nor written to any log.

---

## Known limitations

- The four external checks (KYC, AML, fraud, credit scoring) and the malware
  scanner are **simulations behind ports**. They are labelled as such in code and
  documentation. No real financial or security service is contacted.
- Static analysis (SpotBugs) sits in an opt-in profile and has **not** been run
  against JDK 25 bytecode yet. If it cannot read class file version 69 that will
  be documented as unavailable rather than silently disabled.
- Spotless enforces imports, indentation and whitespace but not full AST
  formatting: google-java-format and palantir-java-format reach into javac
  internals not verified on JDK 25 here.
- The document-status projection is eventually consistent with the document
  context. This can only delay a submission, never wrongly permit one, but it
  does mean a submission may be refused for a document that has in fact just been
  accepted. Documented in the migration that creates the table.
- Only the application service exists. The workflow, document and audit services
  have POMs and dependencies but no source yet.

---

## Exact next step

Phase 4: the Workflow Service — a durable state machine in PostgreSQL that
consumes `application.submitted`, schedules the KYC, AML, fraud and credit checks
through ports with configurable simulators, applies Resilience4j timeouts,
bounded retries and circuit breakers to the synchronous calls, and publishes
`workflow.completed` or `workflow.failed` through its own outbox.
