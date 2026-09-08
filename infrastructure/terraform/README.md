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
| `iam` | One least-privilege role per service, bound to its ServiceAccount by EKS Pod Identity | yes |
| `ecr` | Immutable tags, scan on push | yes |
| `eks` | Private-endpoint cluster, access entries rather than aws-auth, IRSA, and nodes whose pods cannot reach instance metadata | yes |
| `cloudwatch` | Log groups and the full alarm set, including outbox age, consumer lag and stuck applications | yes |
| `secrets` | Secret containers whose values Terraform never learns, with per-secret resource policies | yes |
| `disaster-recovery` | AWS Backup vault, lock and plan — protects against a MISTAKE, which replication does not | yes |
| `budgets` | Optional cost guardrail; refuses to create anything without both an amount and an address | yes |
| `api-gateway` | REST API behind WAF, VPC Link to the internal NLB | yes |
| `internal-load-balancer` | Internal NLB for the VPC Link, forwarding to the ALB the Helm chart creates | yes |

**Every module now has a caller.** `scripts/validate-terraform.sh` still
validates any module no environment references, precisely so an uncalled module
cannot rot unnoticed — it currently reports none.

### The edge chain

```
client -> API Gateway (WAF, JWT authorizer, throttling, usage plans, access logs)
       -> VPC Link
       -> internal NLB          (Terraform: this repository)
       -> internal ALB          (AWS Load Balancer Controller, from the chart's Ingress)
       -> pods
```

Two load balancers, because two constraints meet and neither bends: a REST API's
VPC Link accepts a **network** load balancer and nothing else, and path routing
is layer 7. The extra hop costs a few milliseconds and one more load balancer
billed hourly; the alternative is an ingress controller inside the cluster,
which trades the hop for a component to operate.

### Modules named in the design but not yet written

**None.** Every module the design named now exists and is called by both
environments.

The cluster ADD-ON roles are created by the `eks` module using IRSA, separately
from the per-service roles the `iam` module creates with Pod Identity — add-ons
are cluster infrastructure, services are this platform's. The Secrets Store CSI
driver deliberately gets no role: its AWS provider uses the pod's identity, which
is why each service is granted its own secret and no other.

The load balancer controller's own IAM policy is **not** in this repository. The
upstream document is several hundred lines and changes between releases, so it is
supplied by the caller from a pinned release rather than transcribed.

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
| checkov 3.3.8 | **533 passed, 0 failed, 37 skipped** |

Every suppression carries a written justification, and most are inline on the
resource rather than global, so the next genuine occurrence of the same check
still fails. The full triage — including two false positives that were *verified*
as such rather than assumed — is in
[`../../docs/security/scanning.md`](../../docs/security/scanning.md).
