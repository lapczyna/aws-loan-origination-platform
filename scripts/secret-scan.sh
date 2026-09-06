#!/usr/bin/env bash
#
# Scans the working tree and the complete Git history for committed secrets.
#
# This is a release gate, not a formality: once a commit reaches a public remote,
# a secret in it must be treated as compromised and rotated, whether or not the
# commit is later removed from history.
#
# Exit code 0 means clean. Any finding is a hard failure.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

if ! command -v gitleaks >/dev/null 2>&1; then
    echo "gitleaks is not installed."
    echo "Install it from https://github.com/gitleaks/gitleaks/releases and re-run."
    exit 127
fi

echo "==> Scanning the working tree"
gitleaks detect --no-git --source . --config .gitleaks.toml --redact --exit-code 1

echo "==> Scanning the complete Git history"
gitleaks detect --source . --config .gitleaks.toml --redact --exit-code 1

echo ""
echo "No secrets found in the working tree or in Git history."
echo "This is one item on the public-release checklist, not the whole of it:"
echo "see docs/public-release-checklist.md, which also requires a human review."
