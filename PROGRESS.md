# Implementation progress

This file is the honest record of what exists, what was actually run, and what
does not exist yet. Anything described as verified was executed locally and its
output observed. Nothing here is aspirational.

**No AWS resource has been created. The repository is private and has not been
pushed to any remote.**

Last updated: 2026-09-08

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
| 12 | End-to-end suite, documentation, public-release review | Done |

---

## Verification, as actually run

Every command below was executed on this machine and its output observed.

```bash
./mvnw clean verify            # 293 tests, 0 failures, 0 errors, 0 skipped
scripts/validate-terraform.sh  # fmt + validate + tflint + checkov, exit 0
scripts/validate-helm.sh       # helm lint + template + kubeconform, exit 0
scripts/secret-scan.sh         # gitleaks, working tree and full history
docker compose -f deploy/compose/docker-compose.yml up -d
scripts/local-smoke-test.sh
docker compose -f deploy/compose/docker-compose.yml down
scripts/check-action-pins.sh  # every GitHub Action pinned to a commit SHA
scripts/check-doc-links.sh    # every referenced document exists
actionlint                     # all four workflows lint-clean
npx @redocly/cli@1.34.3 lint docs/api/openapi.yaml
npx @mermaid-js/mermaid-cli@11.4.2   # all 8 README diagrams rendered
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
| `tests/end-to-end` | — | 18 |
| **Total** | **219** | **74** |

**293 tests, 0 failures, 0 errors, 0 skipped.**

### Scanner results

| Scanner | Result |
|---|---|
| gitleaks — working tree | no leaks found |
| gitleaks — full history | no leaks found |
| `terraform fmt -check -recursive` | clean |
| `terraform validate` — dev, prod-example, uncalled modules | valid |
| tflint | clean, exit 0 |
| checkov | 296 passed, **0 failed**, 33 skipped |
| `helm lint` / `helm template` / kubeconform | clean |
| actionlint 1.7.7 — all four workflows | clean |
| `scripts/check-action-pins.sh` | every action SHA-pinned |
| `scripts/check-doc-links.sh` | every referenced document exists |
| Redocly 1.34.3 — OpenAPI 3.1 | valid |
| mermaid-cli 11.4.2 — 8 README diagrams | all render |

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

### Phase 12 — End-to-end suite, documentation, release review

**The end-to-end suite: 16 scenarios, all passing.** The four services run as
four separate operating-system processes launched from their real executable
jars, against shared PostgreSQL, Kafka and LocalStack containers. Real HTTP,
real Kafka delivery, real outbox draining, real presigned uploads with no
credentials, real Flyway migrations, real token validation. Nothing passes
between services as a Java object.

| Class | Covers |
|---|---|
| `LoanOriginationJourneyIT` | Draft, two presigned uploads, scan, projection, submit, assessment, decision, outbox drain, audit chain, no personal data anywhere |
| `SubmissionSemanticsIT` | Repeated idempotency key, key reused for another application, six concurrent submissions, missing mandatory document, abandoned upload, editing after submission |
| `AssessmentOutcomesIT` | Business rejection, inconclusive to manual review and a reviewer's decision, transient failure that recovers, permanent failure, credit score below threshold, credit score in the review band |
| `EventDeliveryIT` | Duplicate event delivery, an outcome redelivered after the decision, and a **broker outage** — the Kafka container is paused mid-submission, the event is held in the outbox, and publication resumes on recovery |
| `OpenApiContractIT` | Generates the API contract from both running services and fails if the committed document has drifted; also asserts no personal data reached the published schemas |

Four bugs in the tests themselves, found by running them rather than reading
them, and each worth recording:

- The happy path asserted `APPROVED` for a **randomly generated** applicant. The
  simulated credit score derives from the applicant reference and the policy
  bands it, so a random applicant approves roughly half the time — a coin flip
  that had already passed twice. Three applicants were computed from that
  derivation so each lands in a known band.
- `TRUNCATE` between tests **deadlocked** against the services' pollers: it needs
  an AccessExclusiveLock on every table while they hold AccessShareLocks.
  PostgreSQL resolved it exactly as it should. Now `DELETE`, with a lock timeout
  and a retry.
- Three assertions raced the events they depended on. One passed in isolation and
  failed only in the full run.
- A workflow event's `aggregate_id` is the **workflow** id; the application id is
  the partition key.

**A `.gitignore` bug found by the suite.** An unanchored `out/` pattern, meant
for an IDE output directory, matched the hexagonal `adapter/out/` directories:
58 source files — every persistence, S3, messaging and external-check adapter —
had never been committed. Nothing failed, because the build reads the working
tree and only git was blind. Every pattern in that section is now anchored, and
the files were scanned with gitleaks before being committed.

**Documentation.** The link checker went from 16 missing documents to none:

- `docs/api/openapi.yaml` — OpenAPI 3.1, valid under Redocly with **zero
  warnings**, and **generated from the running services** rather than written by
  hand. See ADR-0016; the first generation run immediately found two
  discrepancies the hand-written version had, which is the argument for
  generating it.
- 11 alarm runbooks, each written for the person the alarm woke: what it means,
  what it does **not** mean, first checks, likely causes, and what not to do.
- `docs/operations/cost.md`, `terraform-state-bootstrap.md`,
  `database-bootstrap.md`.
- `docs/public-release-checklist.md`.
- **15 ADRs** plus a template and an index, each recording what the decision
  cost as well as what it bought.
- A README architecture section with **8 Mermaid diagrams**, every one verified
  by rendering it — which caught a real error: `call` is a reserved keyword in
  Mermaid flowcharts, so the retry diagram would have shown a parse error
  instead of a picture.
- `tests/performance` — two k6 scripts, deliberately not run by default and not
  in CI, because a load test on a shared runner measures the runner.
- `scripts/local-token.sh`, extracted so the performance README's instructions
  actually work.

---

## Known limitations

- The four external checks (KYC, AML, fraud, credit scoring) and the malware
  scanner are **simulations behind ports**. They are labelled as such in code and
  documentation. No real financial or security service is contacted.
- **No CI has ever executed.** All four workflows exist and are lint-clean
  under actionlint, but the repository has no remote, so GitHub Actions has
  never run a job. "The YAML is valid and the same commands pass locally" is a
  weaker claim than "the pipeline is green", and only the weaker one is made.
- The OpenAPI document is **generated from the running services** and committed,
  with `OpenApiContractIT` failing the build if the two drift. The remaining
  limitation is narrower: three documentation-only types describe the error
  responses, because Spring's `ProblemDetail` is map-backed and generates a
  schema that says nothing about what `errorCode` can contain. They describe the
  real wire shape and the integration tests assert those codes against real
  responses, but nothing mechanically ties the two together.
- **The k6 scripts have never been run against anything.** They are excluded from
  the build and from CI on purpose, and their thresholds are therefore guesses
  rather than measurements.
- **Trivy and the dependency review have never run**, because CI has never run.
  No container image in this repository has been vulnerability-scanned.
- **The Compose stack has not been re-run since Phase 8.** The end-to-end suite
  covers the same journey against the real jars, but the images and the Compose
  wiring were last exercised then.
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

## What is left

All twelve phases are complete. What remains is not implementation:

1. **Run `make release-check` end to end** on a machine with GNU make, and
   record the result. Each step has been run individually; the target itself has
   not, because make is not installed here.
2. **The human security review** in
   [`docs/public-release-checklist.md`](docs/public-release-checklist.md). The
   scanners find what somebody thought to write a pattern for; a person reading
   the tree and the history finds what nobody anticipated. That review has not
   happened, and it is the gate before this repository could be made public.
3. **Nothing has been deployed, and nothing should be** without reading
   [`docs/operations/cost.md`](docs/operations/cost.md) first. The `prod-example`
   environment costs several hundred dollars a month before a single application
   is submitted, and six of its seven largest line items are billed whether or
   not anyone uses it.

The repository is private, has no remote, and has never been pushed.
