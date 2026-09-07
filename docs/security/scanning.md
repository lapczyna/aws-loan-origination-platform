# Security scanning

This document records what is scanned, what the scanners currently report, and
the justification for every suppression. It is the companion to `.checkov.yaml`
and `.gitleaks.toml`.

The rule the repository is held to:

> A suppression without a written justification is not acceptable in review.
> Silencing a scanner without understanding the finding is how a real problem
> ships.

---

## Scanners

| Scanner | Version | Scope | How it is run |
|---|---|---|---|
| gitleaks | 8.30.1 | working tree **and full history** | `scripts/secret-scan.sh` |
| tflint | 0.64.0 | `infrastructure/terraform`, recursive | `scripts/validate-terraform.sh` |
| checkov | 3.3.8 | `infrastructure/terraform` | `scripts/validate-terraform.sh` |
| kubeconform | 0.8.0 | rendered Helm output | `scripts/validate-helm.sh` |
| `helm lint` / `helm template` | 4.2.4 | the chart | `scripts/validate-helm.sh` |

The three downloaded binaries live in a scratch directory **outside the
repository**. No scanner binary is committed.

---

## Current results

Run on 2026-09-07 against the tree at the Phase 10 commit.

| Scanner | Result |
|---|---|
| gitleaks, working tree | no leaks found |
| gitleaks, full history (8 commits) | no leaks found |
| `terraform fmt -check -recursive` | clean |
| `terraform validate` (dev, prod-example, uncalled modules) | valid |
| tflint | clean, exit 0 |
| checkov | **296 passed, 0 failed, 33 skipped** |

Every one of the 33 skips is justified below or inline in the file it concerns.

---

## Where a suppression lives, and why it matters

A skip in `.checkov.yaml` is **global**: it silences that check for every
resource in the repository, now and in future. That is the wrong instrument for
"this one resource is a considered exception", because it also hides the next
genuine occurrence.

So the default is an inline `# checkov:skip=<ID>:<reason>` comment on the
resource itself. `.checkov.yaml` carries only checks that are inapplicable to
the entire codebase.

The clearest illustration is `CKV_AWS_157` (RDS Multi-AZ). Development
deliberately sets `multi_az = false`, because a standby doubles the instance
charge continuously to protect against a zone failure that is, in development,
an inconvenience. Suppressing that globally would also stop the scanner
complaining if somebody turned Multi-AZ off **in production**. The suppression
is therefore attached to the `module "database"` block in
`environments/dev/main.tf` and nowhere else.

---

## Findings that were fixed rather than suppressed

The first checkov run over the finished Terraform reported 25 failures across
16 distinct checks. Five were genuine gaps and were fixed:

| Check | Gap | Fix |
|---|---|---|
| `CKV2_AWS_12` | The VPC's default security group permits all traffic between its members, and anything launched without an explicit group lands in it | `aws_default_security_group` is now managed with **no rules at all**, so the default is closed rather than permissive |
| `CKV2_AWS_60` | A snapshot taken from the DR replica carried no tags — so it could not be attributed or found by a tag query, exactly when someone is looking for it in a hurry | `copy_tags_to_snapshot = true` on the replica |
| `CKV_AWS_237` | Replacing the API Gateway REST API would destroy it before creating the replacement, leaving a visible window with no API | `create_before_destroy` on `aws_api_gateway_rest_api` |
| `CKV2_AWS_53` | The module header cites request validation as a reason for choosing REST API over HTTP API, and no validator was actually configured | `aws_api_gateway_request_validator` validating **parameters**; body validation needs a per-method schema model, which a greedy proxy route has no place for, and that limitation is stated in the code rather than glossed over |
| `CKV2_AWS_31` | The WAF blocked silently: metrics said how many requests matched a rule and nothing said which, or why | `aws_wafv2_web_acl_logging_configuration`, with `authorization`, `cookie` and `x-api-key` **redacted at the WAF** so the values never reach CloudWatch Logs, and allowed requests dropped because the access log already records them |
| `CKV_AWS_18` | The **audit** bucket had no server access logging, though the documents bucket did — so "who read the audit trail" was the one question the audit trail could not answer about itself | `aws_s3_bucket_logging`, targeting a separate bucket |
| `CKV2_AWS_61` | The audit bucket had no lifecycle configuration, so seven years of records sat in S3 Standard and abandoned multipart parts were billed indefinitely | A lifecycle transitioning to `GLACIER_IR` and aborting incomplete uploads — and **no expiration rule**, deliberately (see below) |

### Two decisions inside those fixes worth stating

**The anonymous-IP rule counts, it does not block.** `CKV2_AWS_77` wants
`AWSManagedRulesAnonymousIpList` on the Web ACL. It is there, in `count` mode.
Plenty of legitimate applicants use a VPN, and blocking them would refuse a loan
application over a privacy-preserving choice. Counting gives the same
visibility — the metric shows exactly how much traffic a block would have
caught — without turning a heuristic into a denial of service for real
customers. Promoting it to `block` is a decision to make with evidence from
that metric, not in advance.

**The audit bucket's lifecycle has no expiration rule, and must not.** Object
Lock would refuse the deletion anyway, so the rule would fail every day
forever — a permanent stream of errors that trains people to ignore the
bucket's alarms. The retention period is expressed exactly once, by the Object
Lock configuration. Transitions are fine: the lock forbids deleting and
overwriting an object version, not moving it between storage classes.

There is an operational trap in that fix, recorded in the code as well as here:
the audit bucket's policy denies `s3:PutLifecycleConfiguration` to every
principal outside `audit_administrator_role_arns`, and that deny applies to
Terraform too. The role that applies the module must be listed there, or the
first apply succeeds — the policy does not exist yet — and every subsequent one
fails with `AccessDenied`.

---

## Justified suppressions

### Inline, scoped to one resource

**`modules/api-gateway/main.tf`**

- **`CKV_AWS_120`, `CKV_AWS_225` — API Gateway response caching.**
  Off on purpose. Responses here are per-applicant and authorisation-dependent,
  so a shared response cache is a cross-tenant data leak one cache-key mistake
  away. The latency saving is not worth that class of bug. `caching_enabled =
  false` states it explicitly rather than leaving it to a default.

- **`CKV2_AWS_51` — API Gateway client certificate.**
  Mutual TLS is not used. Callers authenticate with an OAuth2 JWT validated by
  the authorizer and, for partners, an API key that carries the usage plan.
  Client certificates would add a certificate lifecycle to operate without
  replacing either control.

- **`CKV2_AWS_31`, `CKV2_AWS_77` — verified false positives.**
  Both were checked rather than assumed. Copying the module to a scratch
  directory and removing **only** the `count` from the three WAFv2 resources
  makes both checks pass with no other change. The configuration is correct;
  checkov's graph engine cannot follow a reference through a count-indexed
  resource. The `count` stays, because the WAF has to remain optional.

**`modules/kms/main.tf`**

- **`CKV_AWS_109`, `CKV_AWS_111`, `CKV_AWS_356` — IAM wildcards.**
  All three are the same finding, and all three are a category error: the checks
  are written for an IAM policy attached to a principal, where `"Resource": "*"`
  means every resource in the account. This is a **key policy**. In a key policy
  `"*"` means *the key this policy is attached to* — there is no other resource
  it could mean, and AWS rejects a key policy that names the key by ARN, because
  the ARN does not exist until the key does.

  The root-account statement is likewise mandatory rather than lax. Without it
  the key is orphaned: IAM policies cannot grant access to a key whose own
  policy does not delegate to the account, and AWS refuses to create a key that
  could become permanently unusable. The real control is that only the second
  statement grants day-to-day use, and it is bound by a `kms:ViaService`
  condition.

**`environments/dev/main.tf`**

- **`CKV_AWS_157` — RDS Multi-AZ.** Off in development. It doubles the instance
  charge continuously to protect against a zone failure that is, here, an
  inconvenience. `prod-example` sets `multi_az = true`, and the check is still
  live there.

- **`CKV_AWS_293` — RDS deletion protection.** Off in development, because
  tearing the environment down and rebuilding it is a routine action.
  `prod-example` sets `deletion_protection = true` and
  `skip_final_snapshot = false`.

### Global, in `.checkov.yaml`

- **`CKV_AWS_144` — S3 cross-region replication.** Not enabled anywhere.
  Replication is a disaster-recovery control with a real and continuous cost.
  For the documents bucket the recovery path is a re-upload by the applicant;
  for the audit bucket, durability comes from versioning and Object Lock rather
  than from a second region. Revisit if the recovery objective changes.

- **`CKV2_AWS_62` — S3 event notifications.** Flagged on the server access-log
  bucket, which is supplied by the caller and is outside this repository's
  scope. The terminal bucket in a logging chain necessarily has neither logging
  nor notifications of its own: logging a bucket into itself is an infinite
  loop, because every log write is an access that must be logged.

- **`CKV_AWS_338` — CloudWatch log retention of at least one year.**
  Application logs are retained for 30 days in production. Keeping logs that may
  contain incidental personal data for a year conflicts with data minimisation.
  The **audit trail**, not the application log, is the long-lived record, and it
  is held for seven years under Object Lock.

- **`CKV_AWS_117` — Lambda functions inside a VPC.** There are no Lambda
  functions in this platform.

---

## What is not scanned yet

Stated so that the absence is not mistaken for a clean result:

- **SpotBugs** sits in an opt-in Maven profile and has not been run against
  JDK 25 bytecode. If it cannot read class file version 69 that will be recorded
  as unavailable, not silently disabled.
- **Container image scanning** (Trivy or Grype) is wired into the CI workflow in
  Phase 11 but has not been run locally.
- **Dependency vulnerability scanning** is likewise a Phase 11 CI step.

---

## If a secret is ever found

`gitleaks` runs over the **full history**, not just the working tree, precisely
because a secret removed in a later commit is still in the repository.

If one is found:

1. **Treat it as compromised and rotate it first.** Rewriting history does not
   un-disclose a value that has been on a disk, in a CI log, or on a clone.
2. Only then consider rewriting history (`git filter-repo`, or BFG).
3. **History is never rewritten automatically by any script or agent in this
   repository.** It requires explicit human approval, because it invalidates
   every existing clone and every commit hash referenced anywhere else.
