# AWS Loan Origination Platform

A production-oriented **reference implementation** of a cloud-native loan
origination platform: Java 25, Spring Boot 4, PostgreSQL, Kafka, and an AWS
target architecture expressed entirely in Terraform and Helm.

> **Nothing in this repository has been deployed.** No AWS resource has been
> created, no image has been pushed, no cluster has been contacted. Local Docker
> Compose is the default and only execution path. See
> [`docs/operations/cost.md`](docs/operations/cost.md) before running anything
> against a real account.

> **Simulated integrations are labelled as simulated.** KYC, AML, fraud, credit
> scoring and malware scanning are represented by ports with local simulators.
> No real financial or security service is contacted anywhere in this codebase.

See [`PROGRESS.md`](PROGRESS.md) for exactly what is implemented, what was
verified, and what is still missing. It is the authoritative status document;
this README describes the design.

---

## Quick start

```bash
make build          # compile + unit tests, no Docker required
make verify         # + integration tests (needs a running Docker daemon)
make local-up       # start PostgreSQL, Kafka, LocalStack and all four services
make local-smoke-test
make local-down     # stops the stack and KEEPS your data
make local-clean    # stops the stack and DELETES the local volumes
```

`make help` lists every target. No target in the Makefile creates, changes or
deletes an AWS resource.

---

## Documentation

Entries in *italics* do not exist yet; they are listed because files in the tree
already link to them, and `scripts/check-doc-links.sh` reports exactly which.

| Document | What it covers |
|---|---|
| [`PROGRESS.md`](PROGRESS.md) | Implementation status, verified commands, limitations — **the honest record** |
| [`SECURITY.md`](SECURITY.md) | Reporting a vulnerability, what protects the data, what is not scanned |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How to work on this, and the rules that are not negotiable |
| [`docs/security/threat-model.md`](docs/security/threat-model.md) | STRIDE, with residual risks stated |
| [`docs/security/data-classification.md`](docs/security/data-classification.md) | What is held, how long, and the honest problem with erasure |
| [`docs/security/incident-response.md`](docs/security/incident-response.md) | Severity, playbooks, what is available to investigate with |
| [`docs/security/scanning.md`](docs/security/scanning.md) | Every scanner result and every suppression's justification |
| [`docs/operations/runbooks/secret-rotation.md`](docs/operations/runbooks/secret-rotation.md) | Rotate first, then consider history |
| [`infrastructure/terraform/README.md`](infrastructure/terraform/README.md) | Module inventory, cost warnings, HA vs DR |
| *`docs/adr/`* | Architecture decision records — Phase 12 |
| *`docs/architecture/`* | Context, containers, sequences, AWS topology — Phase 12 |
| *`docs/api/openapi.yaml`* | The OpenAPI 3.1 contract — Phase 12 |
| *`docs/operations/cost.md`* | Cost drivers — Phase 12 |
| *`docs/public-release-checklist.md`* | What must happen before this is made public — Phase 12 |

## Continuous integration

Four workflows in [`.github/workflows/`](.github/workflows/), **none of which
has ever executed** — the repository has no remote. Every third-party action is
pinned to a commit SHA, enforced by
[`scripts/check-action-pins.sh`](scripts/check-action-pins.sh).

| Workflow | Trigger | What it will not do |
|---|---|---|
| `pull-request.yml` | PR, push to main | Hold an AWS credential, push an image, plan Terraform |
| `publish-images.yml` | release, or manual + literal `PUBLISH` | Run on a push |
| `terraform-plan.yml` | manual only | Apply — the file contains no `terraform apply` |
| `deploy.yml` | manual only | Deploy without a typed `DEPLOY`, a protected environment, and an immutable digest per service |

There is deliberately **no path from a merge to a deployment**.

---

## Licence

Apache License 2.0. See [`LICENSE`](LICENSE).
