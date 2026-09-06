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
| 3 | PostgreSQL migrations, idempotency, outbox, REST API | Not started |
| 4 | Kafka contracts and Workflow Service | Contracts done; service not started |
| 5 | Document Service and presigned S3 upload | Not started |
| 6 | Audit Service | Not started |
| 7 | Observability and safe logging | Not started |
| 8 | Docker and Docker Compose | Not started |
| 9 | Kubernetes and Helm | Not started |
| 10 | Terraform | Not started |
| 11 | CI/CD and security scanning | Not started |
| 12 | End-to-end verification and public-release review | Not started |

---

## Completed work

### Phase 0 — Environment gate

- Docker Desktop started and the daemon confirmed responding (`docker info` →
  `27.0.3 | linux`).
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
  `.env.example`, `Makefile`, `LICENSE`.

Two compatibility problems were found and fixed here rather than at runtime:

1. `resilience4j-bom:2.4.0` does not list the Spring Boot 4 starter, so
   `resilience4j-spring-boot4` is version-managed explicitly in the parent POM.
2. Spring Framework 7 removed `spring-jcl` and now depends on the real
   `commons-logging` artifact, so the Enforcer rule banning it had to be
   removed — it would have banned Spring itself.

### Phase 2 — Domain model

`services/application-service` domain layer, with no Spring, JPA or Jackson
imports anywhere in `domain/`:

- `LoanApplication` aggregate: no setters, every state change is a named
  business operation that checks its own preconditions and records a domain
  event. Business version increments once per operation and is carried on every
  event so consumers can detect out-of-order delivery.
- `ApplicationStatus` state machine with the legal transitions declared in one
  exhaustive `switch`.
- `ApplicantDetails` is the only type holding personal data. It is deliberately
  a class and not a record, because a record's generated `toString()` would
  print a name, an email address and a date of birth into any log statement that
  touched it. Its `toString()` returns `ApplicantDetails[redacted]`.
- `ApplicantReference` — an irreversible HMAC-SHA-256 pseudonym under a secret
  pepper. This is the only applicant identifier that crosses a context boundary.
- Value objects: `Money` (integer minor units, never floating point),
  `LoanTerm`, `LoanRequest`, `Decision`, `ApplicationId` (RFC 9562 version 7
  UUID, so identifiers are unguessable but still index-friendly).
- `ProductRules` — cheap local eligibility rules evaluated before any external
  check is requested.

### Phase 4 (partial) — Event contracts

`shared/event-contracts` with the versioned envelope, nine payload records
across three bounded contexts, the shared vocabulary enums, and `EventJson` —
the single canonical Jackson 3 mapping used by every producer and consumer.

The compatibility suite replays nine frozen golden samples and asserts that each
still deserialises, that a round trip loses no field, that a field added by a
newer producer does not break an older consumer, that timestamps are ISO-8601
strings, and that no payload declares a field whose name suggests personal data.

---

## Verified commands

Each of these was run and its output observed.

```bash
./mvnw -v                                            # Maven 3.9.16, JDK 25.0.2
./mvnw -B verify -Pfast                              # reactor resolves, all modules build
./mvnw -B -pl shared/event-contracts test            # 23 tests, 0 failures
./mvnw -B -pl shared/event-contracts,services/application-service -am test -Pfast
                                                     # 145 tests, 0 failures
```

Test totals at this point: **145 passing, 0 failing, 0 skipped.**

---

## Known limitations

- The four external checks (KYC, AML, fraud, credit scoring) and the malware
  scanner are **simulations behind ports**. They are labelled as such in code and
  documentation. No real financial or security service is contacted.
- Static analysis (SpotBugs) is in an opt-in profile and has **not** been run
  yet against JDK 25 bytecode. If it cannot read class file version 69 it will be
  documented as unavailable rather than silently disabled.
- Spotless enforces imports, indentation and whitespace but not full AST
  formatting: google-java-format and palantir-java-format reach into javac
  internals that are not verified on JDK 25 here.

---

## Exact next step

Phase 3: Flyway migrations for the `application` schema, the JPA persistence
adapter and mapper, the idempotency store, the transactional outbox with a
`FOR UPDATE SKIP LOCKED` publisher, and the `/v1` REST API with RFC 9457 Problem
Details — then the Testcontainers PostgreSQL integration tests that prove the
outbox and idempotency guarantees.
