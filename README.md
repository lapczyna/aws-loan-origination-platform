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

| Document | What it covers |
|---|---|
| [`PROGRESS.md`](PROGRESS.md) | Implementation status, verified commands, limitations |
| `docs/architecture/` | System context, containers, sequences, AWS topology |
| `docs/adr/` | Architecture decision records |
| `docs/api/openapi.yaml` | The OpenAPI 3.1 contract |
| `docs/operations/` | Runbooks and cost drivers |
| `docs/security/` | STRIDE threat model, data classification |
| `docs/public-release-checklist.md` | What must happen before this repository is made public |

---

## Licence

Apache License 2.0. See [`LICENSE`](LICENSE).
