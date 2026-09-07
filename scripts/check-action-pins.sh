#!/usr/bin/env bash
# =============================================================================
# Refuses any GitHub Action referenced by a tag or a branch instead of a commit
# SHA.
#
# WHY THIS IS WORTH A SCRIPT
# --------------------------
# A tag is a movable pointer. Whoever controls an action's repository can
# repoint v4 at different code, and that code then runs inside this
# repository's jobs, with this repository's token. It has happened in the wild.
# A commit SHA cannot be repointed.
#
# The tag stays in a trailing comment so a human can still read the version, and
# Dependabot understands that convention well enough to update both together --
# so pinning does not mean going stale.
#
# actionlint does not check this, which is why it is here rather than there.
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

if [ ! -d .github/workflows ]; then
    echo "No .github/workflows directory; nothing to check."
    exit 0
fi

bad=0

while IFS= read -r entry; do
    # entry is "path:lineno:      - uses: owner/repo@ref # comment"
    spec="$(printf '%s' "${entry}" | sed -E 's/.*uses:[[:space:]]*//; s/[[:space:]].*$//')"

    case "${spec}" in
        # A local action is this repository's own code, already reviewed here.
        # A docker:// reference is pinned by its own digest or tag elsewhere.
        ./*|docker://*) continue ;;
    esac

    ref="${spec##*@}"

    if ! printf '%s' "${ref}" | grep -Eq '^[0-9a-f]{40}$'; then
        echo "  ${entry}"
        bad=1
    fi
done < <(grep -rnE '^[[:space:]]*(-[[:space:]]*)?uses:' .github/workflows/)

if [ "${bad}" -ne 0 ]; then
    echo ""
    echo "The lines above reference an action by tag or branch rather than by"
    echo "commit SHA. A tag can be repointed by whoever owns the action, and the"
    echo "replacement code would run with this repository's token."
    echo ""
    echo "Pin it:    uses: owner/repo@<40-character-sha> # <tag>"
    exit 1
fi

echo "Every third-party action is pinned to a commit SHA."
