# Security

## What this repository is

A **reference implementation**. It has never been deployed, it processes no real
data, and every external check it performs is a simulation. That shapes what a
security report against it means: there is no running system to compromise, so a
finding here is a design or code defect that would matter *if* somebody deployed
it — which is exactly why it is worth reporting.

There is one exception, and it is the serious one: **a credential or personal
data committed to this repository would be a real disclosure**, whether or not
anything is deployed. See [Reporting](#reporting-a-vulnerability).

---

## Reporting a vulnerability

**Do not open a public issue.** A public issue is a disclosure with no fix
available.

Use GitHub's private vulnerability reporting (Security → Report a vulnerability)
on this repository. If that is unavailable, contact the repository owner
privately through their GitHub profile.

Please include what you would want if you were fixing it: the affected file or
component, what an attacker gains, and the smallest reproduction you have. If
you have a working exploit, describe the class of problem rather than pasting
the exploit itself.

Expect an acknowledgement within a few days. This is a personal project, not a
staffed product, and that is stated plainly rather than promising a response
time nobody is on call to meet.

### If you find a committed secret

Report it privately and **treat it as compromised from the moment it was
committed**. It does not matter whether the commit is recent or whether the
value was later removed: a public repository is cloned, mirrored and indexed.

The remediation order is fixed, and rotation comes first:

1. **Rotate the credential.** Rewriting history does not un-disclose a value
   that has already been on a disk, in a CI log, or in somebody's clone.
2. Confirm the new credential works and the old one is revoked.
3. *Only then* consider removing it from history (`git filter-repo`, or BFG).

**History is never rewritten automatically here.** No script and no agent in
this repository will do it. It requires explicit human approval, because it
invalidates every existing clone and changes every commit hash any external
system may have recorded. The procedure is in
[`docs/operations/runbooks/secret-rotation.md`](docs/operations/runbooks/secret-rotation.md).

---

## Supported versions

There is no release stream and no backporting. `main` is the only supported
state of this repository.

---

## What the platform does to protect data

Summarised here; the reasoning lives next to the code, and the full analysis is
in [`docs/security/threat-model.md`](docs/security/threat-model.md).

### Applicant identity is pseudonymised before it is stored anywhere

`ApplicantReference` is an irreversible **HMAC-SHA-256** pseudonym computed
under a secret pepper. The pepper is read **from a file**, never from an
environment variable — environment variables appear in `/proc`, in crash dumps,
in `docker inspect`, and in the output of anything that enumerates a process's
environment for debugging.

`ApplicantDetails` is deliberately a class rather than a record, so that it can
override `toString()` to return `ApplicantDetails[redacted]`. A record would
have generated one that prints every field, and that generated method is what
runs when the object reaches a log statement, an exception message, or a
debugger transcript.

### The audit trail is tamper-evident and append-only

Each record's hash covers its canonical fields **and the previous record's
hash**, so altering any record invalidates every record after it. That is
enforced at two levels:

- **The database refuses it.** The runtime role is granted `SELECT, INSERT` on
  `audit.audit_record` and nothing else. Verified live: PostgreSQL rejects
  `UPDATE` and `DELETE`.
- **The chain detects it.** Editing or deleting a row as a superuser was
  performed against a real database, and the chain verification detected both.

Audit summaries are governed by an **allow-list** of 17 code-like keys with a
128-character value limit — not a deny-list of things that look sensitive. A
deny-list fails open on anything nobody thought of.

### Nothing logs what it should not

There is **no generic request/response logging filter anywhere**, on purpose:
such a filter logs whatever a field happens to contain, so a new field starts
being logged with nobody deciding it should be. Explicit safe-summary objects
are used instead, with a redaction layer as a second defence. Tests assert that
synthetic markers — a fake national ID, an email address, a JWT, a presigned
URL — never reach the log pipeline.

### Presigned URLs are narrow and never logged

Uploads go directly to S3 through a short-lived presigned `PUT` bound to a
content type. Object keys are opaque identifiers carrying **no personal data**,
because S3 server access logs record the key. Uploads land in a quarantine
prefix and are only moved to the accepted prefix after scanning.

### Defence in depth at the edge and in the pod

- JWTs are validated **at API Gateway** *and again* by each service. The second
  validation is what protects the services if anything is ever deployed with a
  route that bypasses the gateway.
- WAF logs redact `authorization`, `cookie` and `x-api-key` **at the WAF**, so
  the values never reach CloudWatch Logs at all.
- API Gateway response caching is off. Responses are per-applicant and
  authorisation-dependent; a shared cache is a cross-tenant leak one cache-key
  mistake away.
- Containers run as a non-root UID with a read-only root filesystem and no
  shell, curl or wget in the runtime image.
- NetworkPolicies default to deny and exclude `169.254.169.254`, so a
  compromised pod cannot reach the instance metadata service.
- Secrets reach pods as **files** through the Secrets Store CSI driver, not as
  Kubernetes Secrets copied into etcd and injected as environment variables.
- The `ingress` template calls `fail` if the scheme is not `internal`, so an
  internet-facing ingress cannot be produced by editing a values file.

### Nothing deploys itself

There is no path from a merge to a deployment. Deploying requires a manual
trigger, a literal `DEPLOY` typed into a text field, a protected environment
with required reviewers configured **outside** the repository, an immutable
image digest per service, and a server-side Helm dry run. See
[`.github/workflows/deploy.yml`](.github/workflows/deploy.yml).

---

## What is scanned, and what is not

Current results and every suppression's justification:
[`docs/security/scanning.md`](docs/security/scanning.md).

| Scanner | Scope | Status |
|---|---|---|
| gitleaks 8.30.1 | working tree **and full history** | clean |
| tflint 0.64.0 | Terraform | clean |
| checkov 3.3.8 | Terraform | 296 passed, 0 failed, 33 skipped |
| kubeconform 0.8.0 | rendered Helm output | clean |
| Trivy | container images | **wired into CI, never run — CI has never executed** |
| SpotBugs | Java bytecode | **not run**; opt-in profile, unverified on JDK 25 |

Stated so the absence is not mistaken for a clean result.

---

## Known security-relevant limitations

These are properties of a reference implementation, not oversights:

- **KYC, AML, fraud, credit scoring and malware scanning are simulations.**
  They are labelled as such in the code. Nothing contacts a real bureau, and no
  file is genuinely scanned for malware. In a real deployment the malware
  scanner port would be backed by GuardDuty Malware Protection or equivalent;
  until it is, the quarantine prefix is the only real control.
- **No CI has ever run.** Every workflow in `.github/workflows/` is unexecuted.
  The checks were run locally through the equivalent Makefile targets, and the
  workflows are lint-clean under actionlint — which is not the same claim.
- **Every AWS identifier is a placeholder**, including account IDs (twelve
  zeroes), role ARNs and registry hosts. Nothing in this repository is
  configured against a real account.
- **The IAM roles the workflows reference do not exist**, and their trust
  policies are described in comments rather than provisioned. A deployment would
  need those written and reviewed; a trust policy scoped to the repository but
  not the ref lets any branch assume the role.
- **Local development key material** is generated by
  `scripts/generate-local-secrets.sh` into a gitignored directory. The script
  refuses to run unless that directory is confirmed ignored. It is for local
  development only and is not usable anywhere else.
