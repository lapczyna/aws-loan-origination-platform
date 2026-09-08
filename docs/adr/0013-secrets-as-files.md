# ADR-0013: Secrets reach pods as files, not environment variables

**Status:** Accepted
**Date:** 2026-09-04

## Context

The services need a database credential, an applicant pepper and an OIDC client
secret. The Kubernetes default is a `Secret` projected as environment variables.

Environment variables are a poor place for a secret and it is worth being
specific about why:

- Readable at `/proc/<pid>/environ` by anything running in the container.
- Captured in crash dumps and by many error reporters.
- Visible in `docker inspect` and in pod specs.
- Inherited by every child process.
- Printed by any diagnostic that dumps the environment, including well-meaning
  ones.

Kubernetes `Secret` objects are additionally only base64-encoded in etcd unless
encryption at rest is configured.

## Decision

Secrets are pulled from AWS Secrets Manager by the **Secrets Store CSI driver**
and mounted as **files**. Applications read the file — Spring's config-tree
support maps a directory of files to properties.

The applicant pepper is read from a file explicitly, in every environment
including the local Compose stack and the tests, so the code path under test is
the one that runs in production.

## Consequences

- Nothing sensitive is in the process environment or in a pod spec.
- No secret is copied into etcd as a Kubernetes `Secret`.
- Rotation can be picked up without a redeploy where the driver's rotation
  reconciler is enabled — though a rotation nobody restarted is a rotation that
  has not happened yet.
- File permissions and the read are auditable.
- **Cost: the CSI driver is a cluster dependency.** A pod cannot start without
  it, which converts a driver outage into a startup failure.
- **Cost: it is AWS-specific** as configured. The port is a file path, so the
  provider can change, but the `SecretProviderClass` cannot.
- **Cost: local development needs the same shape**, hence
  `scripts/generate-local-secrets.sh` writing to a gitignored directory — and the
  script refuses to run unless that directory is confirmed ignored.

## Alternatives considered

**Kubernetes Secrets as environment variables.** The default, and the reason for
this ADR.

**Kubernetes Secrets as mounted files.** Better, and still copies the secret into
etcd.

**Fetch from Secrets Manager in application code at startup.** Removes the driver
but puts credential-fetching logic and its failure modes into every service.
