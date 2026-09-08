# ADR-0016: The OpenAPI document is generated from the code, and committed

**Status:** Accepted
**Date:** 2026-09-08
**Supersedes:** the hand-written `docs/api/openapi.yaml` introduced in Phase 12

## Context

The API contract was first written by hand. It was accurate when written,
carefully worded, and valid OpenAPI 3.1 — and it was already the weakest
document in the repository, for a reason that has nothing to do with the care
taken over it.

**A hand-written specification drifts, and it drifts silently.** Rename a field,
add a status code, change a constraint: nothing fails. The document stays
plausible and becomes wrong. That is worse than having no document, because
people trust it and build against it.

The first generation run demonstrated the point immediately. The hand-written
document omitted `sizeBytes` from the document representation, and gave
`emailAddress` a `maxLength` of 254 where the code enforces 320. Neither was
careless; both were the ordinary consequence of maintaining two descriptions of
one thing.

## Decision

Generate the document from the running services, and commit the result.

- **springdoc** produces each service's document from its controllers, its DTOs
  and their bean-validation annotations, so the published constraints are the
  constraints actually enforced.
- **The descriptions live on the code.** `@Operation`, `@ApiResponse` and
  `@Schema` carry the prose that used to live in the YAML — next to the field it
  describes, where it gets updated when the field does.
- **`OpenApiContractIT` merges the two services' documents and fails the build
  if the committed file no longer matches.** Regenerating is
  `-Dlos.openapi.write=true`.

Two supporting decisions:

**The document is still committed**, not generated on demand. A contract that
exists only at runtime cannot be reviewed in a diff, and a breaking change to a
public API is exactly the thing that should be visible in a pull request.

**Keys are sorted and lines are never wrapped** before writing. springdoc does
not guarantee an ordering, and an unordered document produces a diff on every
regeneration that has nothing to do with the API — which is how a drift check
gets disabled by the third person who hits it.

## Consequences

- The document cannot be wrong about a path, a method, a status code or a
  constraint, because it is derived from them.
- A change to the public contract shows up as a diff in a pull request, and a
  diff larger than the change that caused it is itself a finding.
- The reasoning moved next to the code, which is where it is most likely to be
  read and maintained.
- **Cost: annotations on the controllers.** They are noise in a class that would
  otherwise be short, and they are the price of the prose surviving generation.
- **Cost: generation needs the services running.** It happens in the end-to-end
  module, because that is the only place both are up. `mvn package` must run
  first.
- **Cost: three documentation-only types** — `ProblemResponse`,
  `MissingDocumentsProblem`, `StateTransitionProblem` — that are never
  instantiated. Errors are returned as Spring's `ProblemDetail`, which is
  map-backed and generates a schema saying nothing about what `errorCode` can
  contain. These describe the real wire shape; the risk that they drift from it
  is bounded by the integration tests, which assert the codes against real
  responses.
- **Cost: some structure was lost.** The hand-written document had named helper
  schemas — `Uuid`, `MinorUnits`, `StatusValue` — that the generator inlines,
  because they were never separate Java types. The generated document is less
  elegant and more truthful.

## A security consequence worth stating separately

`/v3/api-docs` is now **disabled in the `aws` profile**. An endpoint that
enumerates every route, parameter and schema has no business being reachable in
production, and it was reachable by any principal holding a valid token —
including a partner with the narrowest scopes. The end-to-end harness enables it
explicitly, which is the only place it is needed.

## Alternatives considered

**Keep writing it by hand.** Rejected for the reason above, demonstrated by two
real discrepancies on the first run.

**`springdoc-openapi-maven-plugin`.** Starts the application at build time and
fetches the document. Rejected because it starts one service in isolation with
no database, which either needs a mock profile that differs from the deployed
configuration or fails outright — and there would still be two documents to
merge.

**Generate but do not commit.** Loses reviewability. A contract change that
nobody sees in a diff is a contract change nobody agreed to.

**Contract-first: write the spec, generate the code.** A genuinely good approach
and the right one when several teams negotiate an API before building it. It
inverts this repository's premise — the domain model leads — and adopting it
would be a much larger change than this ADR describes.
