#!/usr/bin/env bash
# =============================================================================
# Generates local-only key material into a GITIGNORED directory.
#
# WHY THE MATERIAL IS GENERATED RATHER THAN COMMITTED
# ---------------------------------------------------
# A key in version control is a key that eventually gets copied somewhere it
# matters, and a public repository with a private key in its history is a
# finding regardless of what the key was for. Generating it per machine means
# there is nothing to leak: each developer's pepper is theirs, and the
# repository never contains one.
#
# The output directory is listed in .gitignore. This script refuses to run if
# that is ever not true.
#
# In AWS these values live in Secrets Manager and are projected into the pod as
# files by the Secrets Store CSI driver. The services read a FILE in both cases,
# so the local path and the deployed path exercise the same code.
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

KEY_DIR="local-dev-keys"

# Refuse to write secrets anywhere Git can see them. This check is the whole
# safety property of the script.
#
# The probe is a path INSIDE the directory rather than the directory itself:
# the .gitignore pattern is directory-scoped ("local-dev-keys/"), and
# check-ignore does not match it against a name that does not yet exist as a
# directory. Probing a child path gives the right answer whether or not the
# directory has been created.
if ! git check-ignore -q "${KEY_DIR}/probe" 2>/dev/null; then
    echo "REFUSING TO CONTINUE: '${KEY_DIR}' is not ignored by Git." >&2
    echo "Add it to .gitignore before generating anything into it." >&2
    exit 1
fi

mkdir -p "${KEY_DIR}"
chmod 700 "${KEY_DIR}"

# --- Applicant pseudonymisation pepper ---------------------------------------
#
# HMAC keying material for ApplicantReference. Must be at least 32 bytes: a
# shorter pepper makes the pseudonyms brute-forceable, because the input space
# (a name plus a date of birth) is small enough to enumerate.
PEPPER_FILE="${KEY_DIR}/applicant-pepper"
if [ -f "${PEPPER_FILE}" ]; then
    echo "Keeping the existing applicant pepper at ${PEPPER_FILE}"
    echo "  (regenerating it would change every pseudonym, orphaning local data)"
else
    head -c 48 /dev/urandom | base64 | tr -d '\n=' | head -c 64 > "${PEPPER_FILE}"
    chmod 600 "${PEPPER_FILE}"
    echo "Generated a new applicant pepper at ${PEPPER_FILE}"
fi

echo ""
echo "Local key material is ready in ${KEY_DIR}/."
echo "It is gitignored, machine-specific, and worthless outside this machine."
