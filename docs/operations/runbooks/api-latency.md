> **Nothing in this repository has been deployed.** These runbooks describe how
> the platform would be operated. No alarm has ever fired, because no alarm has
> ever existed.

# Runbook: API latency is high

**Alarm:** `api-latency-p99`

---

## Why p99 and not the average

The average hides exactly the customers who are suffering. A p99 of three seconds
with an average of 80ms means one request in a hundred is painful, and those are
often the largest or most complex — which on this platform means the submissions
that matter most.

## Where the time goes

```
client → API Gateway → VPC link → NLB → pod → PostgreSQL
                                          └→ S3 (presigned URL generation)
```

`Latency` is the total the client experienced. `IntegrationLatency` is the part
spent behind the gateway. The difference is the gateway itself — the authorizer,
WAF, throttling.

**Check that difference first.** A large gap with normal integration latency
points at the authorizer, not at the application, and every hour spent profiling
the service is wasted.

## First checks

1. **One route or all of them?** Status polling is the highest-volume read; it
   being slow is different from submission being slow.
2. **Correlated with a deployment?** Roll back first.
3. **Is the database slow?** Check `rds-saturation` and the slow-query log. Most
   latency incidents here end at the database.
4. **Are the pods CPU-throttled?** A container at its CPU limit shows latency
   with no obvious cause. Check throttling, not utilisation — a pod can be
   throttled while utilisation looks moderate.
5. **JVM pauses?** Long GC pauses show as periodic latency spikes rather than a
   raised floor.

## Likely causes

| Cause | Sign | Action |
|---|---|---|
| Database contention | Slow queries, lock waits | [`rds-saturation.md`](rds-saturation.md) |
| Connection pool exhausted | Latency rises with no matching database load | The pool is the bottleneck, not the database. Check pool size against `DatabaseConnections`. |
| Cold starts after a deploy | Latency spikes then settles | Expected. Check the startup probe allows enough time. |
| Authorizer cache misses | Gateway latency high, integration normal | The authorizer caches for five minutes; a flood of distinct tokens defeats it. |
| Presigned URL generation | Only upload-request routes affected | Signing is local and fast; slowness here means the SDK is resolving credentials repeatedly. |
| A noisy neighbour | One partner's traffic spiked | The usage plan should have throttled them. Check whether the plan is applied. |

## What not to do

**Do not turn on API Gateway response caching to fix latency.** Responses here
are per-applicant and authorisation-dependent; a shared cache is a cross-tenant
data leak one cache-key mistake away. It is off deliberately, and it is recorded
as a suppressed scanner finding with that reasoning.

**Do not raise the integration timeout to stop the 504s.** The timeout is shorter
than the client's on purpose, so the gateway gives up first and returns a clean
504 rather than leaving the client to guess. Raising it converts a fast failure
into a slow one.

## Related

[`api-errors.md`](api-errors.md) · [`rds-saturation.md`](rds-saturation.md)
