# Architecture decision records

Why things are the way they are, and what each choice cost.

An ADR here is written to be read by someone who arrives later and is about to
"simplify" something. It records the constraint that made the code look strange,
and — this is the part most ADRs skip — **what got worse** as a result. An ADR
listing only benefits is marketing.

Use [`_template.md`](_template.md) for a new one. Never edit an accepted ADR to
change a decision: write a new one and mark the old superseded.

| # | Decision | Status |
|---|---|---|
| [0001](0001-hexagonal-architecture-and-bounded-contexts.md) | Hexagonal architecture with four bounded contexts | Accepted |
| [0002](0002-rds-postgresql-rather-than-aurora.md) | RDS for PostgreSQL rather than Aurora | Accepted |
| [0003](0003-transactional-outbox.md) | A transactional outbox rather than publishing directly | Accepted |
| [0004](0004-versioned-event-contracts-with-golden-samples.md) | Versioned event contracts with frozen golden samples | Accepted |
| [0005](0005-simulated-external-checks.md) | External checks are simulations behind ports | Accepted |
| [0006](0006-rest-api-rather-than-http-api.md) | API Gateway REST API rather than HTTP API | Accepted |
| [0007](0007-durable-workflow-state-machine.md) | A durable workflow state machine, not a synchronous chain | Accepted |
| [0008](0008-simulated-malware-scanning.md) | Malware scanning is simulated, behind a port | Accepted |
| [0009](0009-hash-chained-append-only-audit-trail.md) | A hash-chained, append-only audit trail | Accepted |
| [0010](0010-pseudonymous-applicant-reference.md) | An HMAC pseudonym for the applicant, peppered from a file | Accepted |
| [0011](0011-presigned-uploads.md) | Direct-to-S3 presigned uploads | Accepted |
| [0012](0012-idempotency-keys.md) | Idempotency keys with a canonical request fingerprint | Accepted |
| [0013](0013-secrets-as-files.md) | Secrets reach pods as files, not environment variables | Accepted |
| [0014](0014-end-to-end-tests-as-separate-processes.md) | The end-to-end suite runs the services as separate processes | Accepted |
| [0015](0015-single-helm-chart-over-a-services-map.md) | One Helm chart templated over a services map | Accepted |

## The two most consequential

**[0007](0007-durable-workflow-state-machine.md)** and
**[0009](0009-hash-chained-append-only-audit-trail.md)**, because both are about
the same thing: making it impossible for the platform to quietly say something
untrue about a person.

An abandoned check records no outcome, so an outage at a bureau can never later
read as a failure by the applicant. And the audit chain means a decision cannot
be altered afterwards without the alteration being detectable.
