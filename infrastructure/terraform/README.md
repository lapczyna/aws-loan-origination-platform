# Infrastructure

Terraform describing the AWS target architecture.

## NOTHING HERE HAS BEEN APPLIED

No `terraform apply` has been run. No remote state exists. No AWS resource was
created. Every account identifier, ARN, domain and certificate in this directory
is a placeholder.

What *was* run, and is reproducible:

```bash
terraform fmt -check -recursive
terraform init -backend=false      # no remote state, no AWS call
terraform validate
tflint
checkov -d .
```

`-backend=false` is the important flag: it configures Terraform without
contacting S3 or DynamoDB, so validation needs no credentials and creates
nothing.

## Cost warning

**EKS, MSK, NAT Gateway, Multi-AZ RDS and cross-region replication are the
dominant cost drivers on this platform.** A `prod-example` environment applied
as written would cost several hundred US dollars per month before a single
application is submitted. See [`../../docs/operations/cost.md`](../../docs/operations/cost.md).

Two safety defaults exist because of this:

| Variable | Default | Why |
|---|---|---|
| `enable_deployment` | `false` | Nothing is created until someone opts in |
| `enable_cross_region_dr_replica` | `false` | A DR replica roughly doubles the database bill |

## Remote state

The state backend is **deliberately not configured in this repository**. State
contains resource attributes and, for some resources, secret values; a backend
block naming a real bucket would publish that bucket's name and the account it
lives in.

Bootstrapping it is a documented one-time step: see
[`../../docs/operations/terraform-state-bootstrap.md`](../../docs/operations/terraform-state-bootstrap.md).

## Layout

```
modules/          reusable, no environment-specific values
environments/     composition; one directory per environment
```

Modules never contain an account identifier, a region default that assumes an
account, or a hard-coded name. Environments supply those.

### Modules that exist

| Module | What it is | Called by an environment? |
|---|---|---|
| `networking` | Three-tier VPC across three AZs; the data tier has no route to a NAT gateway at all | yes |
| `kms` | One customer managed key per data domain, so "who can decrypt the audit trail" is answerable separately from "who can decrypt the documents" | yes |
| `rds-postgresql` | RDS for PostgreSQL, Multi-AZ standby for HA and an optional cross-region replica for DR — which are [different things](#ha-is-not-dr) | yes |
| `msk` | MSK with IAM authentication only, `min.insync.replicas=2`, unclean leader election off | yes |
| `s3-documents` | Quarantine and accepted prefixes, presigned-upload CORS, lifecycle | yes |
| `s3-audit` | Object Lock, versioning, an explicit deny on deletion, access logging | yes |
| `ecr` | Immutable tags, scan on push | yes |
| `cloudwatch` | Log groups and the full alarm set, including outbox age, consumer lag and stuck applications | yes |
| `budgets` | Optional cost guardrail; refuses to create anything without both an amount and an address | yes |
| `api-gateway` | REST API behind WAF, VPC Link to an internal NLB | **no — see below** |

`api-gateway` has no caller yet, because the `eks` and
`internal-load-balancer` modules it would attach to are not written. It is still
formatted, validated and scanned: `scripts/validate-terraform.sh` validates every
module that no environment references, precisely so an uncalled module cannot rot
unnoticed.

### Modules named in the design but not yet written

`eks`, `internal-load-balancer`, `secrets`, `iam`, `disaster-recovery`. Listed
here rather than omitted, so the gap is visible.

### HA is not DR

Conflated often enough to be worth stating plainly, and stated again at the top
of `modules/rds-postgresql/main.tf`:

| Multi-AZ standby (HA) | Cross-region read replica (DR) |
|---|---|
| Synchronous replication | Asynchronous replication |
| Zero data loss on failover | Data loss bounded by replica lag |
| Automatic failover, ~1–2 min | Manual promotion, one-way |
| Same region | Another region |
| Not readable | Readable, and it lags |

Neither is a backup. Both replicate a mistaken `DELETE` as faithfully as a
legitimate one. Backups and point-in-time recovery are what protect against a
mistake; these protect against infrastructure failure.

## Scanner results

Last run 2026-09-07, via `scripts/validate-terraform.sh`:

| Check | Result |
|---|---|
| `terraform fmt -check -recursive` | clean |
| `terraform validate` — dev, prod-example, uncalled modules | valid |
| tflint 0.64.0 | clean, exit 0 |
| checkov 3.3.8 | **296 passed, 0 failed, 33 skipped** |

Every suppression carries a written justification, and most are inline on the
resource rather than global, so the next genuine occurrence of the same check
still fails. The full triage — including two false positives that were *verified*
as such rather than assumed — is in
[`../../docs/security/scanning.md`](../../docs/security/scanning.md).
