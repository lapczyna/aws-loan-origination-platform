# Data classification and retention

What this platform holds, how sensitive each thing is, where it lives, and how
long it is kept.

**No real personal data has ever been processed by this platform.** Every value
in the local stack is synthetic.

---

## Classes

| Class | Meaning | Handling |
|---|---|---|
| **Restricted** | Identifies a person, or reveals their financial position | Encrypted with a customer-managed key; never logged; never in an object key; never in an error message |
| **Confidential** | Internal, not personally identifying | Encrypted at rest; may appear in logs as an identifier |
| **Internal** | Operational data | Standard handling |
| **Public** | Safe to publish | No constraint |

---

## The data

### Restricted

| Data | Where it lives | Notes |
|---|---|---|
| Applicant name, date of birth, national identifier | `application.loan_application`, encrypted column-store under the database KMS key | Held in `ApplicantDetails`, which is a **class, not a record**, so `toString()` can return `ApplicantDetails[redacted]`. A record's generated `toString()` prints every field, and that is the method that runs when the object reaches a log line or an exception message |
| Email address, phone number | Same | Never echoed by validation errors — errors name the field, never the value |
| Uploaded documents | S3 documents bucket, KMS-encrypted | Object keys are **opaque identifiers containing no personal data**, because S3 server access logs record the key |
| Income, employment, financial position | `application.loan_application` | |
| Credit score and check outcomes | `workflow` schema | The *outcome* is restricted; the fact a check ran is confidential |
| Decision and its reasons | `application`, and the audit trail | |

### Confidential

| Data | Where | Notes |
|---|---|---|
| `ApplicantReference` | Everywhere an applicant is referred to across contexts | An **irreversible HMAC-SHA-256 pseudonym** under a secret pepper. Not reversible, but still a consistent identifier for one person, so it is not public |
| Application identifier | Everywhere | Opaque; safe to log |
| Correlation and causation identifiers | Logs, events, audit records | Safe to log — that is their purpose |
| Audit summaries | `audit.audit_record` | Governed by an **allow-list** of 17 code-like keys with a 128-character limit. An allow-list, not a deny-list: a deny-list fails open on anything nobody anticipated |
| Source IP, user agent | API Gateway access logs | **Personal data under GDPR.** Retained because abuse investigation genuinely needs it; bounded by the log group's retention. A recorded trade-off, not an oversight |

### Internal

Application logs (structured JSON, allow-listed fields), metrics, traces,
Kubernetes events, Terraform state — state is *internal* but handled as
restricted, because it can contain secret values.

### Public

The source code, the OpenAPI document, the architecture documentation. Every
account identifier, ARN, hostname and email address in this repository is a
placeholder.

---

## Retention

| Data | Retention | Why that number |
|---|---|---|
| Audit trail | **7 years** (2555 days), S3 Object Lock GOVERNANCE | The regulatory floor for lending records in most European jurisdictions. GOVERNANCE rather than COMPLIANCE by default: COMPLIANCE cannot be undone by anyone, including root and AWS support, and that is a commitment to make deliberately |
| Loan applications | 7 years after the decision | Same basis |
| Uploaded documents | 7 years after the decision | |
| Quarantined uploads | 7 days (3 in dev) | Never scanned, never referenced, and billed for as long as they exist |
| Application logs | 30 days production, 7 days dev | Deliberately short. Logs may contain incidental personal data, and keeping them for a year to satisfy a scanner rule conflicts with data minimisation. The **audit trail**, not the log, is the long-lived record |
| API access logs | 365 days | They contain source IPs; a year is the balance between investigation and minimisation |
| VPC flow logs | 90 days production, off in dev | The largest CloudWatch Logs contributor |
| WAF logs | Same as access logs | Blocked and counted requests only; allowed requests are dropped, because the access log already has them |
| Metrics | CloudWatch default | Aggregate, no personal data |
| Terraform state | Indefinite, versioned | Deleting state orphans real resources |

### A note on the audit bucket's lifecycle

It transitions to `GLACIER_IR` after 90 days and **has no expiration rule at
all**. That is deliberate twice over: Object Lock would refuse the deletion
anyway, and a lifecycle rule that fails every day forever is a permanent stream
of errors that trains people to ignore the bucket's alarms. Retention is stated
exactly once, by the Object Lock configuration.

---

## Data subject rights, and the honest problem with erasure

| Right | How it would be served |
|---|---|
| Access | Query by `ApplicantReference`; the applicant's own data is returned through the API |
| Rectification | A new application version; history is preserved, because an audit trail that can be edited is not an audit trail |
| Portability | Export of the applicant's own records |
| **Erasure** | **Genuinely constrained — see below** |

Erasure conflicts directly with a seven-year regulatory retention obligation and
with an append-only, hash-chained audit trail. Both conflicts are real, and
neither is solved by pretending otherwise.

The approach this design takes:

1. **Restricted personal data is erasable.** Name, contact details and documents
   can be deleted once the retention obligation expires.
2. **The audit trail is not, and must not be.** It holds `ApplicantReference` —
   a pseudonym — and allow-listed code-like summary values. It holds no names,
   no email addresses and no document contents, by construction.
3. **Crypto-shredding is the mechanism where erasure is required before the
   retention period ends**: destroying the KMS key renders the ciphertext
   permanently unreadable. It is coarse — it applies to everything under that
   key — which is one more reason the keys are separated by data domain.

The pseudonym itself deserves care. HMAC under a secret pepper is irreversible
in the sense that it cannot be inverted, but it is *linkable*: the same applicant
always produces the same reference. Under GDPR that makes it pseudonymised data,
**not** anonymised data, and it remains personal data. Treating it as anonymous
would be the comfortable reading and the wrong one.

---

## Where the classification is enforced rather than merely documented

A classification nobody enforces is a document. These are the enforcement
points:

- `ApplicantDetails.toString()` returns `[redacted]` — enforced by the type.
- `AuditSummary` allow-list — enforced at runtime, rejecting the key by name
  and never echoing its value.
- Log-capture tests assert synthetic markers (fake national ID, email, JWT,
  presigned URL) never reach the log pipeline — enforced by the build.
- ArchUnit forbids `domain` from importing Spring, JPA or Jackson, which keeps
  serialisation decisions out of the layer holding personal data — enforced by
  the build.
- Object keys are validated against `^[A-Za-z0-9/_-]{1,512}$`, so a key cannot
  carry a name or an email address — enforced at runtime.
- The database's runtime role has `SELECT, INSERT` only on the audit table —
  enforced by PostgreSQL, verified live.
