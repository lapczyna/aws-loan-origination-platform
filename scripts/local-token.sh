#!/usr/bin/env bash
# =============================================================================
# Prints an access token from the LOCAL mock OIDC issuer, and nothing else.
#
# Useful on its own -- for a curl by hand, or for the k6 scripts in
# tests/performance -- which is why it is a script rather than a block copied
# out of local-smoke-test.sh each time.
#
#   TOKEN="$(scripts/local-token.sh)"
#   curl -H "Authorization: Bearer ${TOKEN}" http://localhost:8081/v1/applications/...
#
# THIS ONLY WORKS AGAINST THE LOCAL COMPOSE STACK. The issuer is a mock, the
# client secret below is a fixed local-only string, and no token it mints is
# valid anywhere else. Nothing here is a credential.
# =============================================================================

set -euo pipefail

ISSUER="${ISSUER:-http://localhost:8090/default}"

# The hostname the SERVICES use to reach the issuer.
#
# This is not a trick for its own sake. The mock issuer derives the "iss" claim
# from the request's Host header, and the services are configured to trust
# "http://oidc-issuer:8080/default" -- the name by which they reach it on the
# Docker network. A token fetched from the host as "localhost:8090" would carry a
# different issuer and be correctly rejected. Sending the header makes the issued
# token identical to one a service would obtain itself.
ISSUER_INTERNAL_HOST="${ISSUER_INTERNAL_HOST:-oidc-issuer:8080}"

# Overridable, so a caller can ask for the reviewer scopes instead.
SCOPES="${SCOPES:-applications:read applications:write documents:read documents:write}"
CLIENT_ID="${CLIENT_ID:-local-smoke-test}"

for tool in curl jq; do
    command -v "${tool}" >/dev/null 2>&1 || {
        echo "${tool} is required but not installed" >&2
        exit 1
    }
done

TOKEN="$(curl -sf -X POST "${ISSUER}/token" \
    -H "Host: ${ISSUER_INTERNAL_HOST}" \
    -d "grant_type=client_credentials" \
    -d "client_id=${CLIENT_ID}" \
    -d "client_secret=local-only-not-a-secret" \
    -d "scope=${SCOPES}" \
    | jq -r '.access_token')"

if [ -z "${TOKEN}" ] || [ "${TOKEN}" = "null" ]; then
    echo "Could not obtain a token from ${ISSUER}. Is the local stack running? Try 'make local-up'." >&2
    exit 1
fi

# The token only, with no trailing newline decoration, so it can be captured
# directly into a variable.
printf '%s' "${TOKEN}"
