# Threat model

STRIDE, applied to the loan origination platform.

**Scope note.** Nothing here has been deployed. This models the architecture the
Terraform and Helm describe, so the mitigations marked *implemented* are
implemented **in code or configuration**, not verified in production. Where a
mitigation is design-only, it says so.

---

## What is being protected, and from whom

The asset that matters is **an applicant's personal and financial data**, and
after it, **the integrity of a lending decision**. A leak is a regulatory and
human harm. A silently altered decision is worse: it is a harm that leaves no
trace, which is why so much of this design is about evidence rather than
prevention.

Attackers worth modelling:

| | Capability | What they want |
|---|---|---|
| **Unauthenticated internet** | Reach the API endpoint | Any data, any foothold |
| **Authenticated applicant** | A valid token for their own application | Someone else's application |
| **Partner integration** | A valid client credential and an API key | Bulk data, or capacity |
| **Malicious insider** | Database access, possibly cluster access | Alter a decision, exfiltrate quietly |
| **Compromised dependency** | Code execution inside a service | Credentials, then everything |
| **Compromised CI** | A repository token, possibly an OIDC role | Deploy their own image |

The insider is the one most designs quietly omit, and the one the audit trail
exists for.

---

## Trust boundaries

```
Internet
  │  ── TLS ──
  ▼
API Gateway (REGIONAL) ── WAF, JWT authorizer, usage plan, throttle
  │  ── VPC Link ──                       ◄── boundary 1: public → AWS edge
  ▼
Internal NLB (no public address)
  │                                       ◄── boundary 2: edge → VPC
  ▼
EKS pods (private subnets)  ── JWT validated AGAIN here
  │                                       ◄── boundary 3: service → data tier
  ▼
RDS / MSK  (data subnets, NO route to a NAT gateway at all)
```

Boundary 3 is the strongest and the cheapest: the data subnets have no default
route. A database or a broker has no legitimate reason to open an outbound
connection, so removing the route removes an exfiltration path entirely rather
than relying on a rule somebody could relax later.

---

## S — Spoofing

| Threat | Mitigation | Status |
|---|---|---|
| Forged or replayed JWT | OAuth2 resource server validating signature, issuer, audience and expiry. Validated at API Gateway **and again in each service** — the second is what protects a service if a route ever bypasses the gateway | Implemented |
| Stolen partner API key | The key is a throttling identity, never an authentication one. A JWT is required as well, so a key alone grants nothing | Implemented |
| Service impersonating another service | Per-service IAM role via IRSA. No shared role, so a compromised document service cannot use the audit service's permissions | Implemented (Helm + Terraform) |
| CI impersonating a deployer | OIDC with a trust policy pinning repository, ref **and** environment. No long-lived AWS key exists to steal | Design; the roles do not exist |
| Pod assuming a node role via IMDS | NetworkPolicy denies `169.254.169.254` | Implemented |

**Residual.** An IdP compromise defeats all of this. The platform does not, and
cannot, mitigate a forged token signed by a trusted key.

---

## T — Tampering

This is the category the architecture is most opinionated about.

| Threat | Mitigation | Status |
|---|---|---|
| Altering an audit record | Hash chain: each record's hash covers its fields **and the previous hash**, so changing one invalidates every record after it | Implemented; tamper detection verified against a real database |
| Deleting an audit record | Runtime role granted `SELECT, INSERT` only. Verified live that PostgreSQL refuses `UPDATE` and `DELETE`. In S3, Object Lock plus an explicit policy deny | Implemented |
| Altering an application's state directly in the database | Optimistic locking plus a state machine that refuses illegal transitions; every change emits an audit event | Implemented — but see residual |
| Replaying or forging a domain event | Inbox de-duplication on event id (`INSERT ... ON CONFLICT DO NOTHING`); consumers are idempotent | Implemented |
| Malicious document upload | Presigned `PUT` bound to a content type and a short expiry; uploads land in **quarantine** and only move to the accepted prefix after scanning | Implemented — the scanner is **simulated** |
| Deploying an unreviewed image | Deploy takes an immutable **digest** per service, not a tag. ECR repositories are configured for immutable tags | Design; never executed |
| Compromised third-party GitHub Action | Every action pinned to a commit SHA. A tag can be repointed by whoever controls the action's repository, and that code runs with the job's token | Implemented |
| Tampered base image | Dockerfiles pin base images by **digest** | Implemented |

**Residual, and it is the important one.** A sufficiently privileged database
user *can* alter a `loan_application` row. What they cannot do is make the audit
trail agree with the alteration: the chain breaks, and the break is detectable.
This is deliberately a **detection** control, not a prevention one. Preventing a
DBA from writing to a database they administer is not achievable; making the
attempt evident is.

---

## R — Repudiation

| Threat | Mitigation | Status |
|---|---|---|
| "I never submitted that application" | Append-only audit trail with a hash chain, correlation and causation identifiers on every event | Implemented |
| "The system approved it, not me" | Manual-review decisions record the deciding principal's pseudonymous subject | Implemented |
| "That check never ran" | Every check outcome is an audit event. Crucially, `abandonCheck` records **no outcome** — "the bureau was down" must never be readable as "the applicant failed" | Implemented |
| An operator quietly removes evidence | See Tampering. Object Lock in GOVERNANCE mode; COMPLIANCE is available and is a deliberate, irreversible choice | Implemented |

---

## I — Information disclosure

The largest category, because the data is the asset.

| Threat | Mitigation | Status |
|---|---|---|
| Personal data in application logs | No generic request/response logging filter **anywhere**. Explicit safe-summary objects, a redaction layer as second defence, and `ApplicantDetails.toString()` returning `[redacted]`. Tests assert synthetic markers never reach the log pipeline | Implemented |
| Personal data in error responses | RFC 9457 Problem Details that never forward an exception message. Validation errors name the field, never the value | Implemented |
| Personal data in S3 access logs | Object keys are opaque identifiers containing no personal data — precisely because access logs record the key | Implemented |
| Bearer token in WAF logs | `authorization`, `cookie` and `x-api-key` redacted **at the WAF**, so the value never reaches CloudWatch Logs | Implemented |
| Presigned URL leaking through a log | Never logged, at any level | Implemented |
| Personal data in database logs | `log_min_duration_statement` rather than full statement logging: statement logging writes bound parameters, which here means names and email addresses | Implemented |
| Cross-tenant leak via a shared cache | API Gateway response caching is **off**, explicitly. Responses are per-applicant and authorisation-dependent | Implemented |
| One applicant reading another's application | Authorisation checked per request against the token's subject; the manual-review queue requires a distinct scope | Implemented |
| Data at rest readable by the wrong principal | Customer-managed KMS keys, **one per data domain**, so "who can decrypt the audit trail" is answerable separately from "who can decrypt the documents" | Implemented |
| Data in transit intercepted | TLS throughout; `rds.force_ssl = 1`, so a client cannot silently negotiate plaintext | Implemented |
| Terraform state disclosure | No backend block committed; state never uploaded as a CI artifact; plan files deleted even on failure | Implemented |
| Secret in the repository | gitleaks over the **full history**, in CI and as a pre-commit hook | Implemented |
| Secrets readable from a pod's environment | Secrets arrive as **files** via Secrets Store CSI, not environment variables. The applicant pepper is read from a file for the same reason: environment variables appear in `/proc`, crash dumps and `docker inspect` | Implemented |

**Residual.** Source IP is retained in API access logs. It is personal data under
GDPR, kept because abuse investigation genuinely needs it, bounded by the log
group's retention. That is a trade-off, recorded rather than hidden.

---

## D — Denial of service

| Threat | Mitigation | Status |
|---|---|---|
| Unauthenticated flood | WAF rate-based rule per source IP, ahead of the authorizer, so a flood is stopped before a token is validated | Implemented |
| One partner exhausting capacity | Per-partner usage plan with its own throttle and daily quota | Implemented |
| A slow external check exhausting threads | Resilience4j: bounded thread-pool bulkhead **with a bounded queue**, per check type, plus an explicit timeout. Retries live in the durable schedule, never in a request thread | Implemented |
| A failing dependency retried into the ground | Circuit breaker; retries have a budget and a terminal `FAILED` state rather than looping forever | Implemented |
| Unbounded outbox growth | Backoff with full jitter; exhausted events are parked, not retried indefinitely; an alarm on outbox age | Implemented |
| A stuck workflow holding a lease forever | Lease-based claiming with expiry, so an abandoned lease is reclaimed | Implemented |
| Storage exhaustion taking the database offline | RDS storage autoscaling with a **ceiling**, so a runaway is bounded | Implemented |
| One connection stalling the database | `idle_in_transaction_session_timeout` — a leaked idle-in-transaction session holds locks and blocks vacuum indefinitely | Implemented |
| Runaway cost as a denial of budget | Budgets module with alert thresholds; `enable_deployment` defaults to false | Implemented |

**Residual.** No cross-region failover is configured by default. Regional
failure is a documented manual promotion with an accepted RTO, not an automatic
one.

---

## E — Elevation of privilege

| Threat | Mitigation | Status |
|---|---|---|
| Container escape to the host | Non-root UID 10001, read-only root filesystem, all capabilities dropped, no shell or package manager in the runtime image | Implemented |
| Pod reaching the node's IAM role | IRSA per service; NetworkPolicy denies IMDS | Implemented |
| Lateral movement between services | Default-deny NetworkPolicies; only declared paths are permitted | Implemented |
| Compromised service reaching the internet | Data tier has no NAT route; workload egress is restricted by policy | Implemented |
| Over-broad IAM | Per-service roles, least privilege, no wildcard resources outside KMS key policies (where `"*"` means *this key*) | Implemented |
| A pull request granting itself deployment rights | Workflow `permissions:` are read-only by default; the environment's required reviewers are configured **outside** the repository, so editing the YAML cannot remove them | Implemented |
| Database credential theft | `manage_master_user_password` — AWS generates, stores and rotates it, so no password passes through Terraform, its plan output, or state. Applications use IAM database authentication and hold no password at all | Implemented |
| Public database access | `publicly_accessible = false`, data subnets, no egress rules | Implemented |
| Bypassing governance retention | Requires `s3:BypassGovernanceRetention`; COMPLIANCE mode available where a regulator demands it | Implemented, GOVERNANCE by default |

---

## The three things most likely to go wrong first

Ranked by likelihood times consequence, rather than by how interesting they are:

1. **A secret committed by accident.** Highest likelihood by a wide margin.
   Mitigated at three points: a pre-commit hook, a CI job scanning full history,
   and a documented rotate-then-rewrite procedure. Even so, this is the one to
   expect.
2. **A logging change quietly leaking personal data.** A new field added to a
   summary object, or a well-meaning `log.debug(request)`. The absence of a
   generic logging filter is the structural defence; the log-capture tests are
   the regression guard. Neither survives someone determined to add a filter.
3. **An over-broad IAM trust policy.** A GitHub OIDC trust policy scoped to the
   repository but not the ref lets *any* branch assume the role — including a
   branch a contributor pushed. The roles here are placeholders; this is the
   review that matters most when they become real.

---

## What is explicitly out of scope

- **Physical and AWS-managed infrastructure security.** AWS's responsibility
  under the shared responsibility model.
- **The identity provider itself.** Modelled as trusted. A compromised IdP
  defeats every authentication control here.
- **The real external bureaus.** They are simulated. A real integration brings
  its own threat model: credential handling, response integrity, and the
  privacy consequences of what is sent to them.
- **Front-end and browser security.** No front end exists in this repository.
- **Insider threat at the AWS account level.** Someone with account root can
  disable a KMS key and delete a bucket. Object Lock in COMPLIANCE mode is the
  only control that resists this, and choosing it is a deliberate, irreversible
  commitment rather than a default.
