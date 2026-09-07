## What this changes

<!-- What behaviour is different afterwards, and why. Not a list of files. -->

## Why

<!-- The problem being solved. If it is a bug, what made it possible. -->

---

## Checks

- [ ] `./mvnw clean verify` passes locally, integration tests included (Docker running, not skipped)
- [ ] New behaviour has a test that fails without the change
- [ ] `make format` applied
- [ ] Any new module or dependency is version-pinned; no `latest`, no snapshot, no release candidate

## Data and secrets

Every box here is a hard requirement, not a preference.

- [ ] No credential, token, private key, certificate or password is added, in the tree **or in the branch's history**
- [ ] No real AWS account identifier, ARN, internal hostname, private IP or organisation name
- [ ] No real applicant data, email address, phone number or document
- [ ] No Terraform state, plan file, `.env` file or kubeconfig
- [ ] Nothing added logs personal data, a token, or a presigned URL — including inside an exception message or a `toString()`

<!--
If a secret HAS been committed at any point on this branch, say so here rather
than quietly force-pushing over it. Rotate it first: rewriting history does not
un-disclose a value that has been on a disk, in a CI log or in a clone. History
is never rewritten automatically in this repository; it needs explicit human
approval, because it invalidates every existing clone.
-->

## Infrastructure

- [ ] Not applicable
- [ ] `scripts/validate-terraform.sh` passes (fmt, validate, tflint, checkov)
- [ ] `scripts/validate-helm.sh` passes (lint, template, kubeconform)
- [ ] Any new checkov suppression is **inline on the resource** and carries a written justification
- [ ] Nothing was applied, planned against AWS, or installed to a cluster

## Anything a reviewer should look at first

<!-- The part you are least sure about. Naming it gets it reviewed properly. -->
