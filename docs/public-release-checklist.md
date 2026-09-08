# Public release checklist

The repository is **private** and has never been pushed to any remote. This is
what must be true before that changes.

> Making a repository public is **irreversible in practice**. Within minutes it
> can be cloned, mirrored, indexed and archived. Nothing below can be undone by
> setting it back to private.

---

## 1. Secrets — every box mandatory

- [ ] `gitleaks detect --source .` over the **full history** is clean
- [ ] `gitleaks detect --no-git --source .` over the working tree is clean
- [ ] A human has read the `.gitleaks.toml` allowlist and agrees every entry is
      genuinely a false positive
- [ ] No `.env`, kubeconfig, `*.tfstate`, `*.tfplan`, `*.pem`, `*.p12` or `*.jks`
      in the tree **or the history**
- [ ] `local-dev-keys/` is gitignored and empty in the history
- [ ] No AWS access key, session token or bearer token anywhere in the history

**A secret found here is compromised already.** Rotate it first; rewriting
history does not un-disclose a value that has been on a disk or in a clone. See
[`operations/runbooks/secret-rotation.md`](operations/runbooks/secret-rotation.md).

## 2. Identifiers and personal data

- [ ] No real AWS account id. Placeholders are twelve zeroes
- [ ] No real ARN, no real registry host
- [ ] No real domain. Hostnames use RFC 2606 reserved domains
- [ ] No internal hostname or private IP range belonging to an organisation
- [ ] No real email address or phone number
- [ ] No real organisation, team or person's name — including in commit metadata
      and `CODEOWNERS`
- [ ] No customer or applicant data, and no real document
- [ ] Test fixtures use obviously synthetic markers

## 3. History, not just the tree

- [ ] `git log --all --stat` reviewed for files added and later deleted
- [ ] Commit messages contain no internal ticket URL, no internal hostname, no
      colleague's name
- [ ] Author name and email on every commit are what the owner intends to publish

Deleting a file in a later commit does not remove it from the history. This
section exists because that is the single most common way a private repository
leaks when it goes public.

## 4. Licensing and provenance

- [ ] `LICENSE` present and correct (Apache-2.0)
- [ ] No copied code from a proprietary codebase
- [ ] Every dependency's licence is compatible; the dependency review denies
      AGPL-3.0 and GPL-3.0
- [ ] No vendored binary. The scanner binaries live outside the repository

## 5. The claims the repository makes about itself

Everything below is a claim a reader will take at face value. Each must be true.

- [ ] `PROGRESS.md` describes what was **actually run**, with real numbers
- [ ] Nothing is described as working that has not been executed
- [ ] The simulations — KYC, AML, fraud, credit scoring, malware scanning — are
      labelled as simulations everywhere they appear
- [ ] "Never deployed" is stated in the README, `SECURITY.md` and `PROGRESS.md`
- [ ] The CI workflows are marked as never having executed
- [ ] Known limitations are listed rather than omitted

## 6. Automated checks

```bash
make release-check
```

- [ ] `./mvnw clean verify` — all tests pass, integration tests **not** skipped
- [ ] `scripts/validate-terraform.sh` — fmt, validate, tflint, checkov
- [ ] `scripts/validate-helm.sh` — lint, template, kubeconform
- [ ] `scripts/check-action-pins.sh` — every action pinned to a SHA
- [ ] `scripts/check-doc-links.sh` — no referenced document is missing
- [ ] `npx @redocly/cli lint docs/api/openapi.yaml`
- [ ] Every scanner suppression carries a written justification

## 7. Repository settings, after publishing

- [ ] Branch protection on `main`: required reviews, required status checks, no
      force push
- [ ] Deployment environments have required reviewers **configured on the
      environment**, not only in the workflow YAML
- [ ] Private vulnerability reporting enabled
- [ ] Secret scanning and push protection enabled
- [ ] Dependabot alerts enabled
- [ ] No repository secret or variable holds a real credential
- [ ] Actions permissions are read-only by default

## 8. A human security review

**Not optional, and not replaceable by the automated checks.** The scanners find
what somebody thought to write a pattern for. A person reading the diff finds the
thing nobody anticipated.

- [ ] Someone other than the author has read the full tree
- [ ] Someone has read the full history, not only the tip
- [ ] Someone has confirmed the threat model matches what the code does
- [ ] The owner has explicitly decided to publish

---

## The final gate

Publishing is a **deliberate human act**. No script, no workflow and no agent in
this repository will change a repository's visibility, and none should be given
the permission to.

Nothing above is a substitute for someone who understands the consequences
deciding to go ahead.
