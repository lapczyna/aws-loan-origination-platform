> **Nothing in this repository has been deployed.** These runbooks describe how
> the platform would be operated. No alarm has ever fired, because no alarm has
> ever existed.

# Runbook: rolling back a deployment

---

## When to roll back rather than fix forward

**Roll back if the incident started at a deployment.** Not "after investigating
whether the deployment caused it" — the correlation is enough. Rolling back is
fast, reversible and well understood; debugging a live regression while customers
are affected is none of those.

Fix forward only when rolling back is impossible: an irreversible migration has
run, or the previous version has a worse defect.

## Rolling back

```bash
helm -n loan-origination history loan-origination-platform
helm -n loan-origination rollback loan-origination-platform <revision>
kubectl -n loan-origination rollout status deployment --timeout=5m
```

Then confirm the thing that alarmed has actually recovered. A rollback that
completes is not the same as an incident that ends.

## The database is the constraint

**Rolling back the application does not roll back a migration.** This is the part
that turns a two-minute rollback into an outage.

The platform uses expand-and-contract for exactly this reason: a migration adds
before it removes, so version N-1 keeps working against the schema version N
left behind. That property holds *only* while the discipline is followed.

Before rolling back, ask whether the deployment included a migration:

| Migration | Rollback safe? |
|---|---|
| Added a nullable column | Yes. The old version ignores it. |
| Added a table or index | Yes. |
| Added a **non-nullable** column with no default | **No.** The old version's inserts will fail. |
| Dropped or renamed a column | **No.** The old version still selects it. |
| Changed a column's type | **No.** |

If the migration was not backward compatible, rolling back the application makes
things worse. Fix forward, and record it as an incident about the migration.

## Why `--atomic` matters on the way in

The deploy workflow uses `--atomic`, so a release that fails to become ready is
rolled back automatically. That is not a nicety: without it a failed upgrade
leaves a half-updated deployment serving traffic — some pods old, some new,
neither consistent — which is worse than either version alone.

If you are here because `--atomic` already rolled back, the cluster is in the
previous state and the question is why the new version never became ready. Start
with the readiness probe: it includes the database, so a new version that cannot
reach PostgreSQL never becomes ready and is rolled back looking like a code
failure.

## Rolling back an image, not a release

The deploy workflow takes an **immutable digest per service**, so redeploying a
known-good digest is deterministic. Tags are not: a tag can be repointed after
review, and then "roll back to v1.4.2" deploys something else.

## After

Record which revision, why, and whether a migration constrained the options.
Whether the rollback was *possible* is as important a finding as the defect.
