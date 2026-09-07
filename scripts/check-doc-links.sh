#!/usr/bin/env bash
# =============================================================================
# Finds references to docs/ files that do not exist.
#
# WHY THIS EXISTS
# ---------------
# Code comments in this repository point at runbooks: an alarm explains itself
# by naming the document describing what to do about it. That is only useful if
# the document is there. A dead link in an alarm description is discovered at
# three in the morning by the person the alarm woke up.
#
# It is deliberately NOT part of the pull-request workflow yet. Several
# documents are still Phase 12 work, so this reports a known and recorded gap
# rather than a regression -- and a check that is always red is a check people
# learn to ignore. It runs as part of `make release-check`, which is the gate
# before considering public release and is not expected to pass yet.
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

missing_count=0

# Every docs/... path mentioned in a tracked file, deduplicated.
refs="$(git grep -hoE 'docs/[A-Za-z0-9_./-]+\.(md|yaml|yml)' -- \
            '*.md' '*.tf' '*.yaml' '*.yml' '*.java' '*.sh' 2>/dev/null \
        | sort -u || true)"

if [ -z "${refs}" ]; then
    echo "No docs/ references found."
    exit 0
fi

while IFS= read -r ref; do
    [ -n "${ref}" ] || continue
    if [ ! -f "${ref}" ]; then
        echo "  MISSING  ${ref}"
        # Where it is referenced from, so the gap is actionable rather than a list.
        git grep -lF "${ref}" -- '*.md' '*.tf' '*.yaml' '*.yml' '*.java' '*.sh' 2>/dev/null \
            | sed 's/^/             referenced by /' || true
        missing_count=$((missing_count + 1))
    fi
done <<< "${refs}"

if [ "${missing_count}" -ne 0 ]; then
    echo ""
    echo "${missing_count} referenced document(s) do not exist."
    echo "A dead link in an alarm description is found at 3am by whoever the alarm woke."
    exit 1
fi

echo "Every referenced document exists."
