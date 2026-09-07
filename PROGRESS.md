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
| actionlint | 1.7.7, downloaded to the scratch directory |
| pre-commit | 3.8.0 |
| GNU make | **not installed** — see below |

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
| 11 | CI/CD and security scanning | Done |
| 12 | End-to-end suite, documentation, public-release review | **Not started** |

---

## Verification, as actually run

Every command below was executed on this machine and its output observed.

```bash
./mvnw clean verify            # 275 tests, 0 failures, 0 errors, 0 skipped
scripts/validate-terraform.sh  # fmt + validate + tflint + checkov, exit 0
scripts/validate-helm.sh       # helm lint + template + kubeconform, exit 0
scripts/secret-scan.sh         # gitleaks, working tree and full history
docker compose -f deploy/compose/docker-compose.yml up -d
scripts/local-smoke-test.sh
docker compose -f deploy/compose/docker-compose.yml down
scripts/check-action-pins.sh  # every GitHub Action pinned to a commit SHA
actionlint                     # all four workflows lint-clean
```

**GNU make is not installed on this machine.** The Makefile is a convenience
wrapper over exactly the commands above, and its targets have not themselves
been executed here. The underlying commands have.

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
- `scripts/local-smoke-test.sh` drives the whole vertical slice and was run: an
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

### Phase 11 — CI/CD and security scanning

Four workflows, **none of which has ever executed.** The repository has no
remote, so GitHub Actions has never run a job. What can be verified locally was:
all four are lint-clean under actionlint 1.7.7, every third-party action is
pinned to a commit SHA, and the checks the pull-request workflow runs are the
same scripts that were run by hand.

| Workflow | Trigger | Status |
|---|---|---|
| `pull-request.yml` | pull request, push to main | Never run |
| `publish-images.yml` | release, or manual with a literal `PUBLISH` | Never run; nothing has been pushed to any registry |
| `terraform-plan.yml` | manual only | Never run; **contains no `terraform apply`** |
| `deploy.yml` | manual only | Never run; nothing has been deployed to any cluster |

The pull-request workflow runs the build with Testcontainers integration tests
(not skipped), Spotless, gitleaks over the **full history**, dependency review,
image builds that are scanned and never pushed, SBOM generation, the Terraform
and Helm validation scripts, and actionlint over the workflows themselves.

Decisions worth recording:

- **Every third-party action is pinned to a commit SHA**, with the tag in a
  trailing comment. A tag is a movable pointer: whoever controls the action's
  repository can repoint `v4` at new code, and that code runs with the job's
  token. `scripts/check-action-pins.sh` enforces it, and was tested against a
  planted tag and a planted branch reference as well as against the real tree.
- **The image scan fails the publish workflow but only reports on a pull
  request.** A base-image CVE with no fix available should not block unrelated
  work; at the point of shipping it must. `ignore-unfixed` keeps the hard stop
  actionable.
- **There is no automatic path from a merge to a deployment.** Deploying needs
  a manual trigger, a literal `DEPLOY` typed into a text field, a protected
  environment whose required reviewers are configured **outside** the
  repository, an immutable image digest per service, and a server-side Helm dry
  run.
- **The deploy gates were tested, not just written.** The digest validator was
  extracted and run against valid digests and against a tag, and rejects the
  tag. The Helm digest contract was proven by rendering the chart with
  `--set-string services.<name>.digest=...` and confirming all four images
  resolve to `...@sha256:...`.
- **gitleaks and kubeconform are downloaded and checksum-verified** in CI rather
  than pulled through a marketplace action, so the versions match the ones used
  locally and no licence key is involved.

Documentation added: `SECURITY.md`, `CONTRIBUTING.md`, `CODE_OF_CONDUCT.md`,
`docs/security/threat-model.md` (STRIDE),
`docs/security/data-classification.md`,
`docs/security/incident-response.md`,
`docs/operations/runbooks/secret-rotation.md`, a pull-request template, a
`CODEOWNERS.example` (named `.example` so no real handles are published), a
Dependabot configuration for Maven, Docker, Actions and Terraform, and an
opt-in pre-commit configuration whose first hook is gitleaks.

---

## Known limitations

- The four external checks (KYC, AML, fraud, credit scoring) and the malware
  scanner are **simulations behind ports**. They are labelled as such in code and
  documentation. No real financial or security service is contacted.
- **No CI has ever executed.** All four workflows exist and are lint-clean
  under actionlint, but the repository has no remote, so GitHub Actions has
  never run a job. "The YAML is valid and the same commands pass locally" is a
  weaker claim than "the pipeline is green", and only the weaker one is made.
- **`docs/` is still incomplete.** Present: `docs/security/` (scanning,
  threat model, data classification, incident response) and
  `docs/operations/runbooks/secret-rotation.md`. Missing, and linked to from
  files already in the tree: the ADRs, the remaining eight runbooks,
  `docs/operations/cost.md`, `docs/operations/terraform-state-bootstrap.md`,
  `docs/api/openapi.yaml`, `docs/public-release-checklist.md`. **Those links are
  currently broken**, and `scripts/check-doc-links.sh` reports exactly which:
  16 referenced documents do not exist. It runs as part of `make release-check`
  — the gate before public release, which is correctly not passing yet — rather
  than in the pull-request workflow, because a check that is always red is a
  check people learn to ignore.
- **Trivy and the dependency review have never run**, because CI has never run.
  No container image in this repository has been vulnerability-scanned.
- The IAM roles the workflows assume **do not exist**, and their trust policies
  are described in comments rather than written as Terraform. A trust policy
  scoped to the repository but not the ref would let any branch assume the role;
  that is the review that matters most when they become real.
- **`tests/end-to-end` is an empty module** — a POM and nothing else. The
  end-to-end path has been exercised through `scripts/local-smoke-test.sh` against the
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
- The Makefile targets have **not been executed on this machine** — GNU make
  is not installed here. Each target is a thin wrapper over a script or a Maven
  invocation, and those were run directly.
- `CODEOWNERS.example` is deliberately not active. Renaming it to `CODEOWNERS`
  does nothing on its own: it only has teeth when the branch protection rule for
  `main` requires review from code owners.

---

## Exact next step

**Phase 12 — end-to-end suite, documentation, public-release review.**

1. `tests/end-to-end`, currently an empty module: the full happy path plus a
   repeated idempotency key, the same key with a different payload, concurrent
   submission, Kafka outage and recovery, a transient external failure, a
   permanent rejection, a manual-review decision, a duplicate event, an
   out-of-order event, a rejected document and a missing mandatory document.
2. `docs/api/openapi.yaml` (OpenAPI 3.1) — the pull-request workflow already
   has a job waiting for it, which currently reports the file's absence rather
   than passing silently.
3. The documentation the tree already links to and does not have: 12+ ADRs, the
   remaining runbooks, `docs/operations/cost.md`,
   `docs/operations/terraform-state-bootstrap.md`, and a README with the
   architecture diagrams.
4. `tests/performance` k6 scripts, not run by default.
5. `docs/public-release-checklist.md` and a final full-history gitleaks scan.

The broken documentation links are the most visible gap: several files written
in phases 9 to 11 reference documents that do not exist yet.
