# ADR-0017: IAM database authentication, and no database password at all

**Status:** Accepted
**Date:** 2026-09-08
**Amends:** [ADR-0013](0013-secrets-as-files.md), which described a database
password mounted as a file. There is no longer a database password to mount.

## Context

The repository disagreed with itself, and had for several phases.

- The `rds-postgresql` module set `iam_database_authentication_enabled = true`.
- `docs/operations/database-bootstrap.md` said the services use IAM
  authentication and hold no password.
- The Helm chart mounted `<env>/los/<service>/datasource-password` into every
  pod, and the `aws` profile read it as `spring.datasource.password`.
- The `iam` module granted both `rds-db:connect` *and*
  `secretsmanager:GetSecretValue` on that password, because it could not tell
  which was real.

Both paths worked. Only one should exist, and an ambiguity like this is not
harmless: it is the kind that gets resolved by whoever is debugging at the time,
in whichever direction is quickest.

The irony was three lines away in the same file. Directly below the datasource,
the Kafka block reads:

> IAM authentication against Amazon MSK. No username, no password, no long-lived
> credential: the pod's IAM role is the credential.

The database was the only thing on the platform still holding a password.

## Decision

**IAM database authentication, in the `aws` profile, with no password anywhere.**

The AWS Advanced JDBC Wrapper obtains a short-lived authentication token from RDS
per connection and refreshes it before expiry. The pod's IAM role is the
credential, exactly as it already is for MSK.

- The `aws` profile's URL carries the `jdbc:aws-wrapper:` scheme, the driver is
  `software.amazon.jdbc.Driver`, and only the `iam` plugin is enabled. The
  wrapper also ships failover and read-write splitting; enabling what is not
  needed adds behaviour to debug during an incident.
- The chart passes host, port and name rather than a prebuilt URL, because the
  IAM plugin needs the bare host separately: the token is signed for a host, and
  one signed for the wrong host is rejected with an error that reads like a bad
  password.
- The per-service `datasource-password` secrets are gone from the `secrets`
  module, from the chart's `SecretProviderClass`, and from the `iam` module's
  grants.
- **Only the application service mounts anything now** — the applicant pepper,
  which only it uses. The other three mount no volume at all.

Local and test profiles are unchanged and still use pgjdbc with a password
against a throwaway container. The wrapper is inert there.

## Consequences

- **There is no database password to rotate, leak, or log.** No file to steal
  from a compromised pod, no value in Secrets Manager, no rotation function to
  write. The `secrets` module now holds exactly one secret.
- Access is granted and revoked in IAM, alongside every other permission, rather
  than by changing a password and restarting four services.
- Connections are attributable: RDS logs the IAM principal.
- **Cost: a runtime dependency**, and one whose failure mode is unfamiliar. A
  misconfigured `iamHost` fails as an authentication error rather than as a
  configuration error.
- **Cost: token expiry becomes a real consideration.** Tokens last fifteen
  minutes. `iamExpiration` is set below that so a connection is never opened
  with a token that expires mid-handshake, and Hikari's `max-lifetime` already
  retires connections well before the server would.
- **Cost: the bootstrap still needs one password.** Creating an IAM database user
  requires a password-authenticated connection as the master user. That password
  is generated and rotated by AWS through `manage_master_user_password` and never
  passes through Terraform. Documented in `database-bootstrap.md`.
- The database is unreachable from a developer's laptop without an IAM identity
  that can generate a token. That is a feature.

## What this change found

Making the username load-bearing surfaced a bug that had been invisible.

The chart built the database user as `los_<context>_runtime`; the `iam` module
granted `rds-db:connect` on `<context>_runtime`; `database-bootstrap.md` showed
`<context>_runtime`. The migration that actually creates the role —
`V2__runtime_role_grants.sql` — says `los_application_runtime`, so the chart was
right and the other two were wrong.

**With a password, a mismatched IAM grant is simply unused, and nothing fails.**
With IAM authentication it is the difference between connecting and not. The
module and the document now match the migration, which is authoritative because
it is the statement that creates the role.

## Alternatives considered

**Keep the password and drop IAM authentication.** Fewer moving parts, and it
would have resolved the ambiguity just as completely. Rejected because it is the
weaker posture in every respect and would have contradicted the RDS module and
the bootstrap document, which were both already committed to IAM.

**Keep both, and let the deployment choose.** This is what the repository already
did by accident, and it is what made the mismatched username invisible.

**A custom `DataSource` calling `RdsUtilities.generateAuthenticationToken`.**
Avoids the dependency, and reimplements token caching, refresh and failure
handling in this codebase. The wrapper is AWS's own and is maintained.

## Not verified

**The token flow has never run.** There is no RDS instance and no AWS account, so
what has been checked is that the dependency resolves, ships in the image, and
that the local and test profiles are unaffected — 293 tests pass. Whether a token
is accepted by a real RDS instance is unverified, like everything else in this
repository that touches AWS.
