> **Nothing in this repository has been deployed.** These runbooks describe how
> the platform would be operated. No alarm has ever fired, because no alarm has
> ever existed.

# Runbook: the API is returning errors

**Alarms:** `api-5xx`, `api-4xx`

---

## 5xx and 4xx are different incidents

| | Means | Whose problem |
|---|---|---|
| **5xx** | The platform failed | Ours. Always investigate. |
| **4xx** | The request was refused | Usually the caller's — *until the rate changes*. |

A rising 4xx rate is worth attention even though each individual response is
correct. A partner that suddenly gets 401s has not changed its code; something
about token validation has. A spike in 403s can be a caller probing scopes.

## First checks, in order

1. **Which route?** The per-method metrics exist for exactly this. "Submissions
   are failing" and "one health check is noisy" have the same API-wide total.
2. **All callers or one?** One partner suggests their credential or their usage
   plan. All callers suggests the platform.
3. **Started when?** Line it up against the last deployment. If they coincide,
   roll back first and investigate afterwards — see
   [`eks-rollback.md`](eks-rollback.md).
4. **Which layer?** Work inwards: WAF, then the authorizer, then the VPC link,
   then the load balancer targets, then the pods.

## Reading the 5xx

The access log carries `errorCode` and a correlation identifier and no bodies.
Take the correlation identifier into the application logs; that is what the field
is for.

| Pattern | Likely cause |
|---|---|
| 504 at the gateway, healthy pods | Integration timeout. The gateway gives up before the client does, deliberately. Check pod latency. |
| 502/503, some pods | Failing readiness. Check the readiness probe — it includes the database. |
| 500 with `INTERNAL_ERROR` | An unhandled exception. The correlation identifier is the only way in; the response deliberately carries nothing else. |
| 5xx only on submit | The mandatory-document projection, or the outbox insert. Check the database. |

## Reading the 4xx

| Code | Meaning | When to worry |
|---|---|---|
| 401 | No token, expired, wrong audience | A spike across all callers means the IdP or the issuer configuration changed |
| 403 | Valid token, missing scope | A spike from one caller may be probing |
| 409 | Idempotency conflict, or an illegal state transition | A spike suggests a client retrying with a reused key — their bug, worth telling them |
| 422 | Mandatory documents missing | A spike suggests the document projection has stalled: applications are being refused for documents that *were* accepted |
| 429 | Throttled | The usage plan is doing its job. Check whether the limit is now wrong. |

**The 422 case is the trap.** It looks like caller error and is a platform
problem: the application service refuses submission because its read model has
not caught up. Check consumer lag on `application-service.document-events`.

## What the response will not tell you

No stack trace, no SQL, no table name, no internal identifier, no exception
message — by design. The correlation identifier is the entire link between what
the caller saw and what the platform logged. It is not an omission to work
around.

## Related

[`api-latency.md`](api-latency.md) · [`eks-rollback.md`](eks-rollback.md) ·
[`rds-saturation.md`](rds-saturation.md)
