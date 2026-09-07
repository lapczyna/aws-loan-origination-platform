# Implementation progress

This file is the honest record of what exists, what was actually run, and what
does not exist yet. Anything described as verified was executed locally and its
output observed. Nothing here is aspirational.

**No AWS resource has been created. The repository is private and has not been
pushed to any remote.**

Last updated: 2026-09-07

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
| checkov | 3.3.8 |

---

## Phase status

| # | Phase | Status |
|---|---|---|
| 0 | Environment gate | Done |
| 1 | Repository baseline and parent build | Done |
| 2 | Domain model and Application Service core | Done |
| 3 | PostgreSQL migrations, idempotency, outbox, REST API | Done |
| 4 | Kafka contracts and Workflow Service | Done |
| 5 | Document Service and presigned S3 upload | Done |
| 6 | Audit Service | Done |
| 7 | Observability and safe logging | Done |
| 8 | Docker and Docker Compose | Done |
| 9 | Kubernetes and Helm | Done |
| 10 | Terraform | Done |
| 11 | CI/CD and security scanning | **Not started** |
| 12 | End-to-end suite, documentation, public-release review | **Not started** |

---

## Verification, as actually run

Every command below was executed on this machine and its output observed.

```bash
./mvnw clean verify            # 275 tests, 0 failures, 0 errors, 0 skipped
scripts/validate-terraform.sh  # fmt + validate + tflint + checkov, exit 0
scripts/validate-helm.sh       # helm lint + template + kubeconform, exit 0
scripts/secret-scan.sh         # gitleaks, working tree and full history
make local-up && make local-smoke-test && make local-down
```

### Test totals

`./mvnw clean verify`, run 2026-09-07 with Docker running, so every
Testcontainers integration test executed rather than being skipped.

| Module | Unit | Integration |
|---|---:|---:|
| `shared/event-contracts` | 23 | — |
| `services/application-service` | 122 | 26 |
| `services/workflow-service` | 32 | 10 |
| `services/document-service` | — | 11 |
| `services/audit-service` | 11 | 9 |
| `tests/architecture` | 31 | — |
| **Total** | **219** | **56** |

**275 tests, 0 failures, 0 errors, 0 skipped.**

### Scanner results

| Scanner | Result |
|---|---|
| gitleaks — working tree | no leaks found |
| gitleaks — full history (8 commits) | no leaks found |
| `terraform fmt -check -recursive` | clean |
| `terraform validate` — dev, prod-example, uncalled modules | valid |
| tflint | clean, exit 0 |
| checkov | 296 passed, **0 failed**, 33 skipped |
| `helm lint` / `helm template` / kubeconform | clean |

Every checkov suppression carries a written justification, and most are inline
on the resource rather than global, so the same check still fails elsewhere. The
full triage is in [`docs/security/scanning.md`](docs/security/scanning.md).

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

A third surfaced in Phase 3 and belongs with them: **Spring Boot 4 moved
auto-configuration out of the aggregate starters into per-technology modules.**
Depending on bare `flyway-core` compiles and then silently never runs a
migration; `spring-kafka` compiles and then provides no `KafkaTemplate` bean.
The services depend on `spring-boot-flyway` and `spring-boot-kafka` instead.

### Phase 2 — Domain model

`services/application-service` domain layer, with no Spring, JPA or Jackson
imports anywhere in `domain/` — a rule now enforced by ArchUnit rather than by
discipline.

- `LoanApplication` aggregate whose transitions are domain methods that refuse
  an illegal move, not setters.
- `ApplicationStatus` with an exhaustive switch declaring the legal transitions,
  tested across the full 9×9 matrix.
- `ApplicantDetails` is deliberately a class rather than a record, so it can
  override `toString()` to return `ApplicantDetails[redacted]`. If it ever
  reaches a log statement, an exception message or a debugger transcript,
  nothing identifying is printed.
- `ApplicantReference` is an irreversible HMAC-SHA-256 pseudonym under a pepper
  read from a **file**, never from an environment variable or a property.

### Phase 3 — Persistence, idempotency, outbox, REST API

- Flyway migrations, one schema per service, with separate migration and runtime
  database roles.
- Transactional outbox claimed with `FOR UPDATE SKIP LOCKED`, exponential
  backoff with full jitter, and a terminal `FAILED` state rather than an
  unbounded retry.
- Idempotency on `Idempotency-Key` plus a SHA-256 fingerprint of the canonical
  request body: the same body replays the original result, a different body is a
  409.
- RFC 9457 Problem Details that never forward an exception message.
- OAuth2 resource server with a `LazyIssuerJwtDecoder`, so an IdP blip cannot
  stop a pod from starting.

### Phase 4 — Event contracts and the Workflow Service

- `shared/event-contracts`: a versioned envelope, a canonical Jackson 3 mapper,
  and **nine frozen golden samples** that 23 compatibility tests replay. Removing
  or renaming a required field fails the build.
- The Workflow Service is a **durable state machine persisted in PostgreSQL**,
  not a chain of synchronous HTTP calls. Work is claimed by lease with
  `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING id`.
- Retries live in the durable schedule, not in an HTTP thread. Resilience4j
  supplies a circuit breaker, a bounded thread-pool bulkhead and an explicit
  timeout per check type.
- `abandonCheck` records **no outcome**. "The bureau was down" must never be
  readable as "the applicant failed".

### Phase 5 — Document Service

- Presigned S3 `PUT` bound to a content type, with a **separate presigner
  endpoint** from the service's own S3 client endpoint — otherwise the URL is
  signed for a route the client cannot reach.
- Quarantine → accepted prefix migration; object keys are opaque and carry no
  personal data.
- `DocumentRejectionRecorder` runs in `REQUIRES_NEW`, because a rejection
  recorded inside the transaction that then threw was rolled back with it.

### Phase 6 — Audit Service

- Append-only, hash-chained records: SHA-256 over the canonical fields plus the
  previous hash, `U+001F` as the field separator, summary keys sorted.
- `AuditSummary` is an allow-list of 17 code-like keys with a 128-character
  value limit. A rejection names the key and never the value.
- `V2__append_only_grants.sql` grants `SELECT, INSERT` only. **Verified live**
  that PostgreSQL refuses `UPDATE` and `DELETE` on `audit.audit_record`, and
  that editing or deleting a row as a superuser breaks the chain detectably.

### Phase 7 — Observability and safe logging

- Spring Boot 4's built-in structured JSON logging; no logstash encoder.
- No generic request/response logging filter anywhere. Explicit safe-summary
  objects instead, with a redaction layer as a second defence.
- Log-capture tests assert that synthetic markers — a fake national ID, an email
  address, a JWT, a presigned URL — never reach the log pipeline.

### Phase 8 — Docker and Compose

- Multi-stage builds on **digest-pinned** Temurin images, non-root UID 10001,
  read-only root filesystem.
- The healthcheck is a bash `/dev/tcp` probe, because the runtime image
  deliberately contains no curl and no wget.
- Compose runs PostgreSQL, Kafka in KRaft mode, LocalStack and a mock OIDC
  issuer. `S3_SKIP_SIGNATURE_VALIDATION=0` is set explicitly — LocalStack
  skips presigned-signature validation by default, which made two tests vacuous
  until it was found.
- `make local-smoke-test` drives the whole vertical slice and was run: an
  application reached `APPROVED`, 16 audit records were written with an intact
  chain, every outbox drained, and no personal data appeared in any log.

### Phase 9 — Kubernetes and Helm

- One chart templated over a `services` map, rather than four near-identical
  charts.
- Per-service ServiceAccount and IAM role; default-deny NetworkPolicies that
  exclude `169.254.169.254`; Secrets Store CSI delivering **files**, not
  Kubernetes Secrets.
- `ingress.yaml` calls `fail` if the scheme is not `internal`, so an
  internet-facing ingress cannot be produced by a values file.
- Verified with `helm lint`, `helm template` and kubeconform. **Never against a
  cluster.**

### Phase 10 — Terraform

Modules: `networking`, `kms`, `rds-postgresql`, `msk`, `s3-documents`,
`s3-audit`, `ecr`, `cloudwatch`, `budgets`, `api-gateway`. Environments: `dev`
and `prod-example`.

- `enable_deployment` defaults to **false**, so a plan produces no resources and
  an apply creates nothing until someone deliberately opts in.
- `enable_cross_region_dr_replica` defaults to **false**; it roughly doubles the
  database bill.
- No backend block is committed, so `terraform init -backend=false` validates
  the configuration with no credentials and no network call.
- HA and DR are treated as different things, and the difference is written down
  where the code is: a Multi-AZ standby is synchronous and automatic; a
  cross-region replica is asynchronous, manually promoted and one-way. Neither
  is a backup.

Two real bugs were found by the scanners rather than by reading:

1. **tflint** reported `project_tag` as declared but unused in the budgets
   module. It was in fact used — inside `"user:Project${var.project_tag}"`,
   where the escaping was wrong in a way that produced a cost filter matching
   nothing, silently. Rewritten as
   `format("user:Project%s%s", "$", var.project_tag)`.
2. **checkov** found that the audit bucket had no server access logging while
   the documents bucket did. "Who read the audit trail" is the one question the
   audit trail cannot answer about itself.

The checkov triage went from 25 failures to zero: seven were genuine gaps and
were **fixed**; the rest are suppressed with written justifications, two of them
as false positives that were *verified* by reproducing the pass in a scratch
copy rather than assumed. See [`docs/security/scanning.md`](docs/security/scanning.md).

---

## Known limitations

- The four external checks (KYC, AML, fraud, credit scoring) and the malware
  scanner are **simulations behind ports**. They are labelled as such in code and
  documentation. No real financial or security service is contacted.
- **`docs/` is almost entirely missing.** Only `docs/security/scanning.md`
  exists. The ADRs, runbooks, cost breakdown, threat model, OpenAPI document and
  README diagrams are Phase 12 work, and files already in the tree link to them.
  Those links are currently broken.
- **`SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md` and `.github/` do not
  exist yet.** There is no CI at all: every check listed above is run by hand
  through the scripts in `scripts/`.
- **`tests/end-to-end` is an empty module** — a POM and nothing else. The
  end-to-end path has been exercised through `make local-smoke-test` against the
  Compose stack, but not as an automated suite.
- `tests/performance` and `tests/security` do not exist. The k6 scripts are
  Phase 12 work.
- Terraform modules named in the design but **not written**: `eks`,
  `internal-load-balancer`, `secrets`, `iam`, `disaster-recovery`. Because
  `eks` and `internal-load-balancer` are missing, the `api-gateway` module has
  no environment that calls it; `scripts/validate-terraform.sh` validates
  uncalled modules separately so that gap cannot rot unnoticed.
- There is **no `environments/local`** for Terraform, deliberately. The local
  environment is Docker Compose. A Terraform "local" environment that provisions
  nothing would be a directory that has to be maintained and proves nothing.
- Static analysis (SpotBugs) sits in an opt-in profile and has **not** been run
  against JDK 25 bytecode. If it cannot read class file version 69 that will be
  documented as unavailable rather than silently disabled.
- Spotless enforces imports, indentation and whitespace but not full AST
  formatting: google-java-format and palantir-java-format reach into javac
  internals not verified on JDK 25 here.
- The document-status projection is eventually consistent with the document
  context. This can only delay a submission, never wrongly permit one, but it
  does mean a submission may be refused for a document that has in fact just been
  accepted. Documented in the migration that creates the table.
- Container image scanning and dependency vulnerability scanning have not been
  run. Both are Phase 11 CI steps.

---

## Exact next step

**Phase 11 — CI/CD and security scanning.** A pull-request workflow running
everything the local scripts run (build, unit, integration, ArchUnit, format,
dependency scan, gitleaks over full history, image build **without push**, image
scan, SBOM, Terraform fmt/validate/tflint/checkov, Helm lint/template,
kubeconform); least-privilege `permissions:` on every job; every third-party
action pinned to a commit SHA with a version comment.

Then the three workflows that exist but are **never executed here**: an ECR
publish (OIDC, immutable digest), a Terraform plan that never applies, and a
manual deploy gated on `workflow_dispatch`, a literal `DEPLOY` confirmation, a
protected environment and required reviewers.

Followed by `SECURITY.md`, the STRIDE threat model, data classification,
retention, an incident-response outline and a secret-rotation runbook.
