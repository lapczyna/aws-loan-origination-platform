# ADR-0015: One Helm chart templated over a services map

**Status:** Accepted
**Date:** 2026-09-06

## Context

Four services with near-identical Kubernetes shapes: a Deployment, a Service, a
ServiceAccount, an HPA, a PodDisruptionBudget, NetworkPolicies, probes, resource
limits and a hardened security context.

The default is a chart per service. Four charts means four copies of the same
templates, and four copies drift: a security-context hardening applied to three
of them is the exact shape of the bug nobody notices, because each chart looks
right on its own.

## Decision

**One chart**, iterating over a `services` map in values. Each entry declares
what actually differs — port, replica count, autoscaling bounds, resources,
ingress paths, and whether anything may connect to it at all.

`exposed: false` on the workflow and audit services is the clearest example of
why this shape helps: they are driven entirely by events and schedulers, nothing
should ever open a connection to them, and saying so once makes it **enforceable**
by both the ingress and the NetworkPolicy rather than merely true today.

## Consequences

- A hardening change applies to every service at once, by construction.
- The values file becomes a readable inventory of the platform.
- Reviewing a change means reading one template, not diffing four.
- **Cost: the templates are more abstract.** `{{- range $name, $service := .Values.services }}`
  is harder to read than a concrete Deployment, and a helper receiving a context
  dict rather than the root is easy to get wrong — `.Release.Name` inside a
  ranged helper resolves to nothing, which cost a debugging session.
- **Cost: services are deployed together.** Releasing one independently means
  either a values override or splitting the chart, and at four services with one
  team the shared release is the simpler trade.
- **Cost: a genuinely different service** — a batch job, something with a
  StatefulSet — does not fit and would need its own chart.

## Alternatives considered

**A chart per service plus a shared library chart.** The textbook answer and
genuinely good above roughly ten services. At four it is more machinery than the
duplication it removes.

**Kustomize.** A reasonable alternative. Rejected because Helm's release
lifecycle — `--atomic`, history, rollback — is what the deployment workflow's
safety depends on.
