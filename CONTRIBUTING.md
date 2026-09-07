# Contributing

## What this project is

A reference implementation of a cloud-native loan origination platform, built to
demonstrate architecture rather than to be operated. It is **deployable but
never deployed**, and contributions have to keep it that way.

---

## The rules that are not negotiable

These come first because everything else is a preference and these are not.

### Nothing gets deployed from this repository

No `terraform apply`. No `terraform destroy`. No `kubectl apply`. No
`helm install` or `helm upgrade`. No mutating AWS CLI call. No deployment
workflow is executed. No image is pushed.

Validation is done with commands that create nothing:
`terraform init -backend=false`, `terraform validate`, `helm template`,
`kubeconform`. If AWS credentials happen to exist on your machine, do not use
them for this project.

### Nothing real gets committed

Never, in the tree or in a branch's history:

credentials of any kind · tokens · passwords · private keys · certificates ·
cookies · real AWS account identifiers or ARNs · real hostnames, domains or
private IPs · real organisation or person names · real email addresses or phone
numbers · applicant data or real documents · Terraform state or plan files ·
`.env` files · kubeconfig files · generated presigned URLs

Placeholders are the convention: account identifiers are twelve zeroes, domains
are on reserved example domains.

If you commit one by accident, **say so** rather than quietly force-pushing.
Rotate it first — rewriting history does not un-disclose a value that has
already been in a clone. See
[`docs/operations/runbooks/secret-rotation.md`](docs/operations/runbooks/secret-rotation.md).

### Do not claim something works unless it was actually run

This applies to commit messages, documentation and `PROGRESS.md`. "The tests
pass" means you ran them and watched them pass. If a check was skipped, say it
was skipped. `PROGRESS.md` is the honest record of the project's state, and a
single optimistic entry makes the whole file worthless.

A simplified implementation is fine. Presenting a simulation as a real
integration is not — the KYC, AML, fraud, credit and malware-scanning adapters
are simulations and are labelled as such everywhere they appear.

---

## Getting set up

You need JDK 25, Docker, and — for the infrastructure checks — Terraform, Helm,
tflint, checkov and kubeconform. Maven is not required; the wrapper is
committed in script-only form.

```bash
pip install pre-commit && pre-commit install   # strongly recommended
make local-secrets                             # gitignored local dev keys
make build                                     # compile + unit tests, no Docker
make verify                                    # + integration tests, Docker required
```

`make help` lists everything.

---

## Before you open a pull request

```bash
make format          # apply the formatting rules
make verify          # unit + integration tests, with Docker actually running
make tf-validate     # if you touched Terraform
make helm-validate   # if you touched Helm
make secret-scan     # always
```

`make release-check` runs the lot.

**Run `make verify` with Docker up.** The integration tests are Testcontainers
tests against real PostgreSQL, Kafka and LocalStack. A suite that silently
degrades to unit tests reports green for something it did not check.

---

## How the code is organised, and why it will push back

Each service is hexagonal:

```
domain/     pure Java. No Spring, no JPA, no Jackson. Enforced by ArchUnit.
usecase/    application services and outbound port interfaces
adapter/    in/{web,messaging}  out/{persistence,messaging,s3,external}
config/     wiring
```

31 ArchUnit rules enforce this, and they are not advisory — they fail the build.
When they wrote themselves the first time they found four genuine design leaks,
so if one fires against your change, read it before working around it.

### Conventions worth knowing before you are corrected on them

- **Domain transitions are methods that refuse illegal moves**, not setters.
- **No Lombok `@Data`** on domain types, entities or DTOs.
- **A type holding personal data is a class, not a record**, so it can override
  `toString()` to redact. A record's generated `toString()` prints every field,
  and that is the method that runs inside a log line or an exception message.
- **No generic request/response logging filter.** Anywhere. Such a filter logs
  whatever a field happens to contain, so a new field starts being logged with
  nobody deciding it should be. Use explicit safe-summary objects.
- **Retries belong in the durable schedule, not in a request thread.**
- **Every version is pinned.** No `latest` tag, no snapshot, no release
  candidate, no milestone.

### Comments

Comments explain **why**, not what. A comment restating the code is noise; a
comment recording the constraint that made the code look strange is the reason
the next person does not "simplify" it back into a bug. The existing code leans
heavily on this — see `budgets/main.tf` for a comment that exists because the
obvious version failed silently.

---

## Commits

Conventional commits, with the body carrying the reasoning.

```
feat(workflow): durable assessment state machine with simulated providers
fix(document): record rejections in a separate transaction
chore(deps): bump testcontainers to 2.0.5
```

Types: `feat` `fix` `docs` `test` `refactor` `perf` `build` `ci` `chore`.

The subject line says what changed. **The body says why, and what you actually
verified.** A commit that says "fixed the outbox" and one that says which
guarantee was broken, how it was reproduced, and what test now covers it are the
same change and not the same commit.

---

## Tests

- A change in behaviour needs a test that fails without it.
- Integration tests use Testcontainers against real infrastructure, not mocks of
  it. Mocking PostgreSQL's locking behaviour proves nothing about `FOR UPDATE
  SKIP LOCKED`.
- Tests that assert privacy — that personal data does not reach a log, that an
  error does not echo a value — are load-bearing. Do not weaken them to make a
  change pass.

---

## Infrastructure changes

- Modules never contain an account identifier, a hard-coded name, or a region
  default that assumes an account. Environments supply those.
- Anything that costs money continuously gets a `COST:` comment saying so.
- New scanner findings must be **fixed or suppressed with a written
  justification**, and the suppression goes **inline on the resource**, not in
  `.checkov.yaml`. A global skip also hides the next genuine occurrence.
  See [`docs/security/scanning.md`](docs/security/scanning.md).

---

## Reporting a security issue

Not through an issue or a pull request. See [`SECURITY.md`](SECURITY.md).
