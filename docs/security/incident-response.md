# Incident response

An outline, not a staffed process. This is a reference implementation with no
on-call rota, and saying so is more useful than a document that describes one
that does not exist. What follows is the shape a real process would take and the
platform-specific facts an incident would depend on.

---

## Severity

Severity is about consequence to a person, not about how alarming the graph
looks.

| | Meaning | Examples |
|---|---|---|
| **SEV1** | Personal data disclosed, or a lending decision may have been altered | Audit chain broken; credential in a public repository; unauthorised access to the documents bucket |
| **SEV2** | The platform cannot make decisions | API down; database unavailable; every check failing |
| **SEV3** | Degraded but correct | Outbox backing up; consumer lag rising; one check provider down |
| **SEV4** | Visible only internally | A stuck workflow; a noisy alarm |

A broken audit chain is **SEV1 even with no other evidence of compromise**. The
chain breaking means the record can no longer be trusted, and an untrustworthy
audit trail is the failure it exists to prevent.

---

## The first five minutes

1. **Declare it.** An unnamed incident has no owner.
2. **Preserve evidence before you fix anything.** Snapshot the database, export
   the relevant log groups, note the current audit chain head. A restart is the
   most common way an investigation loses the thing it needed.
3. **Assign one incident lead.** They coordinate and do not debug.
4. **Contain, then fix.** Revoking a credential is containment; deploying a
   patch is a fix. Containment comes first.
5. **Start a timeline immediately.** Reconstructing one afterwards is guesswork,
   and a regulator's first question is when you knew.

---

## Playbooks

### A credential is disclosed

**Rotate first. Always.** Rewriting history does not un-disclose a value that
has been on a disk, in a CI log or in a clone.

1. Rotate the credential and confirm the new one works.
2. Revoke the old one and confirm it is refused.
3. Review CloudTrail for use of the old credential — this is what tells you
   whether it is an incident or a near miss.
4. Only then consider rewriting history, with **explicit human approval**. It
   invalidates every clone and changes every commit hash. No script or agent in
   this repository does it automatically.

Full procedure:
[`../operations/runbooks/secret-rotation.md`](../operations/runbooks/secret-rotation.md).

### The audit chain is broken

Treat as SEV1 and as evidence tampering until proven otherwise.

1. **Do not repair the chain.** A repaired chain destroys the evidence of what
   was altered. The break is the finding.
2. Identify the first broken record. Everything before it is still verifiable;
   everything after it is in question.
3. Compare against the S3 copy under Object Lock. The database copy is the
   working one; the S3 copy is the one that survives someone with database
   access. A divergence localises the tampering.
4. Pull CloudTrail and database audit logs for the window.
5. Remember what the design already tells you: the runtime role holds
   `SELECT, INSERT` only. A successful `UPDATE` or `DELETE` means the change was
   made by a superuser or by a migration, which narrows the question
   considerably.

### Personal data may have been disclosed

1. Establish scope: which applicants, which fields, over what window.
2. Contain: revoke access, rotate the relevant KMS grants if needed.
3. **The 72-hour GDPR notification clock starts at awareness, not at
   confirmation.** Involve legal early; it is not the engineering team's call.
4. Preserve every log that establishes what was actually accessed. S3 server
   access logs on the documents and audit buckets are the record of who read
   what.

### The platform is down

1. Check the layers in order: API Gateway 5xx, then load balancer targets, then
   pod readiness, then RDS and MSK.
2. **Applications in flight are not lost.** The outbox holds unpublished events
   and durable workflows resume; that is what those mechanisms are for. Resist
   the instinct to replay manually — duplicate delivery is handled, but manual
   replay bypasses the ordering the workflow depends on.
3. If the cause is a recent deployment, roll back rather than debug forward:
   `helm -n loan-origination rollback loan-origination-platform <revision>`.

### A malicious document was uploaded

The scanner in this repository is **simulated**. In a real deployment:

1. The object is already in the quarantine prefix and was never moved to
   accepted — that is what the prefix is for.
2. Confirm nothing else read it: the documents bucket's access logs.
3. Block the applicant reference from further uploads.
4. If it *was* promoted to accepted, that is a scanner failure, and the scope is
   every document promoted in the same window.

---

## What is available to investigate with

Because it cannot be reconstructed afterwards if it was not being captured at
the time:

| Source | What it answers | Retention |
|---|---|---|
| Audit trail (PostgreSQL + S3) | What happened to an application, in order, tamper-evident | 7 years |
| API Gateway access logs | Who called what, from where, with what result | 365 days |
| WAF logs | What was blocked and why (`authorization`, `cookie`, `x-api-key` redacted at the WAF) | 365 days |
| Application logs | Structured JSON, allow-listed fields, correlation ids | 30 days |
| VPC flow logs | What actually talked to what | 90 days, production only |
| S3 access logs | Who read which object | Per the log bucket |
| CloudTrail | Which principal called which AWS API | Account-level |
| RDS logs | Connections, slow statements, lock waits | Per log group |

Correlation identifiers thread through all of them, which is the difference
between an investigation and a search.

---

## Afterwards

A post-incident review within a week, written down, blameless, and answering:

- What did we know, and when?
- Which control was supposed to catch this, and why did it not?
- What would have detected it sooner?
- What is the smallest change that makes this class of incident less likely?

**Blameless is a practical requirement, not a courtesy.** The person who
understands what happened is usually the person who made the mistake, and a
review that punishes them is a review that stops being told the truth.

Every action item gets an owner and a date, or it is not an action item.

---

## What does not exist here

Stated so nobody discovers it during an incident:

- No on-call rota, no paging integration, no escalation policy.
- The SNS alarm topic has **no subscriptions**, deliberately — a committed email
  address is a real address in a public repository.
- No incident-management tooling is configured.
- No tabletop exercise has been run.
- The runbooks referenced above are Phase 12 work and are not all written yet.
