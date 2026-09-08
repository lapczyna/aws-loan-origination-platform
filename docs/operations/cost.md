# Cost

> **Nothing in this repository has been deployed, so nothing has been billed.**
> Every figure below is an estimate for `eu-west-1` at on-demand list prices,
> intended to convey **magnitude and ranking**, not to be accurate to the dollar.
> Prices change; check the AWS pricing pages before making a decision on these
> numbers.

---

## The one paragraph to read

Applied as written, the `prod-example` environment costs **several hundred US
dollars per month before a single loan application is submitted**, and the
largest items are billed *hourly whether or not anyone uses the platform*. This
is not a system that costs nothing when idle. That is why `enable_deployment`
defaults to `false`.

---

## What dominates, in order

| Component | Rough monthly | Billed on | Idle cost |
|---|---:|---|---|
| **MSK** — 3 × `kafka.m7g.large` + storage | ~$400–550 | Per broker-hour | **Full** |
| **RDS Multi-AZ** — `db.r7g.large`, 200 GB gp3 | ~$350–450 | Per instance-hour, doubled for the standby | **Full** |
| **EKS control plane** | ~$73 | Fixed hourly | **Full** |
| **NAT gateways** — 3, one per AZ | ~$100 + data | Per gateway-hour **plus per GB processed** | **Full** |
| **EKS nodes** — 3 × `m7g.large` | ~$200 | Per instance-hour | **Full** |
| **Cross-region DR replica** (off by default) | ~+$350 + transfer | Per instance-hour | **Full** |
| API Gateway REST | ~$3.50 / million requests | Per request | None |
| S3 — documents and audit | ~$25 per TB-month | Storage + requests | Low |
| CloudWatch Logs | ~$0.57 per GB ingested | Ingestion + storage | Low |
| KMS | $1 per key-month | Per key + requests | ~$4 |
| WAF | ~$5 + $1 per rule + per-request | Per Web ACL | Low |

**Six of the top seven are idle costs.** A platform processing zero applications
costs almost exactly what one processing a thousand a day costs.

---

## The decisions that actually move the bill

### MSK is the largest item, and the least elastic

Three brokers run continuously. `min.insync.replicas=2` with a replication factor
of 3 is the configuration being tested, and two brokers would not exercise it —
so three is the floor, not a choice.

The lever is **broker size**. Development uses `kafka.t3.small` and costs roughly
a tenth of production's `m7g.large`. If this platform were being built for real
at low volume, MSK Serverless would be worth measuring against provisioned before
committing.

### Multi-AZ doubles the database, and is not optional in production

The standby is a synchronous copy in another Availability Zone. It doubles the
instance charge, continuously, and provides **no read capacity** — it is not
queryable.

What it buys is zero data loss on failover. That is not a cost optimisation
target for a system making lending decisions. Development sets `multi_az = false`
because a zone failure there is an inconvenience.

### NAT gateways are billed twice, and the second one surprises people

Per gateway-hour **and** per gigabyte processed. Three gateways is correct for
production — one shared gateway makes a single zone failure take out egress
everywhere — but it triples the hourly charge.

The per-GB charge is why the **S3 gateway endpoint** matters: document uploads
and audit exports would otherwise be billed as NAT data processing. It is both a
security improvement and a real saving. `single_nat_gateway = true` exists for
development.

### The DR replica roughly doubles the database bill

Off by default even in `prod-example`. It is an instance running continuously in
another region, plus cross-region transfer for everything it replicates.

Before enabling it, be clear what it is not: not a backup (it replicates a
mistaken `DELETE` faithfully), not zero RPO (it lags), and not automatic
(promotion is manual and one-way). See
[`runbooks/cross-region-replica-promotion.md`](runbooks/cross-region-replica-promotion.md).

### Log retention is a cost lever pointing the wrong way

Application logs are kept 30 days. Longer would cost more *and* conflict with
data minimisation, since they may contain incidental personal data. The audit
trail — not the log — is the seven-year record, and it lives in S3 under Object
Lock, where the transition to `GLACIER_IR` after 90 days is what keeps seven
years affordable.

Shortening log retention to save money is the wrong trade in the other direction:
30 days is roughly the window in which an incident is still investigable.

### REST API over HTTP API costs about 3.5× per request

A deliberate trade, recorded in ADR-0006. HTTP API cannot be associated with a
WAF, has no usage plans or API keys, and cannot do request validation. At this
platform's request volumes the difference is small in absolute terms; at very
high volume it would be worth revisiting.

---

## Reducing the bill for a demonstration

If the goal is to show the architecture working rather than to run it:

1. **Use the `dev` environment.** `kafka.t3.small`, `db.t4g.micro`, one NAT
   gateway, no flow logs, short retention. Roughly a fifth of production.
2. **Use Docker Compose instead.** The entire platform runs locally with no AWS
   account at all, and `scripts/local-smoke-test.sh` drives the full journey
   through it. This is the intended way to see it work.
3. **Destroy it afterwards.** The idle cost is the whole cost. An environment
   left running over a weekend costs the same as one under load.

---

## Guardrails already in the configuration

| Guardrail | Effect |
|---|---|
| `enable_deployment = false` | Every module's count is zero. A plan produces no resources; an apply creates nothing. |
| `enable_cross_region_dr_replica = false` | The most expensive optional component is opt-in. |
| Budgets module | Off unless given both an amount and a notification address, so it cannot be half-configured. |
| Storage autoscaling **ceiling** | A runaway is bounded rather than unbounded. |
| ECR lifecycle policy | Old images are expired rather than accumulating. |
| Every `COST:` comment in the Terraform | Anything billed continuously says so where it is configured. |

---

## What is not estimated here

Data transfer between Availability Zones, request-level charges at low volume,
Secrets Manager, Route 53, ACM (free for AWS-integrated use), and support plans.
At the scale above they are noise next to MSK and RDS — but "noise" is a claim
about this shape of workload, not a general one.
