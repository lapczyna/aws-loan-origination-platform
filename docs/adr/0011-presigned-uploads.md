# ADR-0011: Direct-to-S3 presigned uploads

**Status:** Accepted
**Date:** 2026-09-03

## Context

Applicants upload supporting documents. The obvious design has the client POST
the file to the API, which streams it to object storage.

That puts every byte through the API Gateway, the load balancer and a JVM thread.
It makes request-size limits an application concern, ties up threads for the
duration of a slow mobile upload, and puts customer documents in the memory of a
service that has no reason to hold them.

## Decision

The client asks for an upload slot and receives a **short-lived presigned `PUT`
URL bound to a content type**. It uploads directly to S3. It then calls a
completion endpoint, and the service asks S3 what is actually at the key,
verifying size, content type and optional checksum against what was declared.

Supporting decisions, each of which matters:

- **Object keys are opaque identifiers with no personal data**, because S3 server
  access logs record the key.
- **No client-supplied filename.** It is user-controlled text and every
  destination is wrong: in the key it leaks through access logs and inventory
  reports, in the database it is personal data with no purpose, in a response
  header it is a content-disposition injection.
- **The presigner has its own endpoint override**, separate from the service's S3
  client. Without it the URL is signed for the route the *service* uses, which a
  browser cannot reach.
- **The response is `Cache-Control: no-store`**, not merely `no-cache`. A
  presigned URL in a shared cache is a write credential handed to whoever reads
  that cache next.
- **The URL is never logged**, at any level.

## Consequences

- Documents never pass through the platform's compute. Threads, memory and
  bandwidth are unaffected by upload size.
- Verification means the service does not take the client's word for what was
  uploaded.
- **Cost: the client must handle a two-step flow**, and an abandoned upload
  leaves a `PENDING_UPLOAD` record. A lifecycle rule expires abandoned quarantine
  objects.
- **Cost: a presigned URL is a bearer credential.** Anyone holding it can write
  until it expires. Short expiry and content-type binding narrow it; they do not
  eliminate it.
- **Cost: CORS is required**, because the browser's PUT is cross-origin.
  Deliberately narrow — only PUT, only configured origins, never a wildcard.

## Alternatives considered

**Proxy the bytes through the API.** Simpler for the client, worse in every other
respect.

**S3 POST policy with a form.** More flexible constraints, considerably more
fiddly, and unnecessary for a single-file PUT.
