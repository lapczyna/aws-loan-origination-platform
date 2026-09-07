#!/usr/bin/env bash
# =============================================================================
# The local happy path, end to end, against the running Compose stack.
#
#   1. obtain a token from the local OIDC issuer
#   2. create a draft application
#   3. request an upload slot for each mandatory document
#   4. PUT the file directly to S3 through the presigned URL
#   5. mark the upload complete and wait for the scan to clear it
#   6. submit the application
#   7. wait for the workflow to reach a decision
#   8. read the status back
#   9. confirm the audit trail recorded it
#
# This is the check that the whole platform works together. The integration
# tests prove each service in isolation against real infrastructure; this proves
# they agree with each other across a network, through real HTTP, real Kafka
# delivery and real presigned uploads.
#
# Everything it touches is local. No AWS account is contacted.
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

APPLICATION_API="${APPLICATION_API:-http://localhost:8081}"
DOCUMENT_API="${DOCUMENT_API:-http://localhost:8082}"
ISSUER="${ISSUER:-http://localhost:8090/default}"
# The hostname the SERVICES use to reach the issuer. The token's "iss" claim
# must match it, so the request below carries it as a Host header.
ISSUER_INTERNAL_HOST="${ISSUER_INTERNAL_HOST:-oidc-issuer:8080}"
LOCALSTACK="${LOCALSTACK:-http://localhost:4566}"

# Bounded so a broken stack fails in a minute rather than hanging a CI job.
MAX_WAIT_SECONDS="${MAX_WAIT_SECONDS:-90}"

RED=$'\033[31m'; GREEN=$'\033[32m'; YELLOW=$'\033[33m'; RESET=$'\033[0m'
step()    { printf '\n%s==> %s%s\n' "${YELLOW}" "$1" "${RESET}"; }
ok()      { printf '    %s%s%s\n' "${GREEN}" "$1" "${RESET}"; }
fail()    { printf '    %s%s%s\n' "${RED}" "$1" "${RESET}"; exit 1; }

require() {
    command -v "$1" >/dev/null 2>&1 || fail "$1 is required but not installed"
}
require curl
require jq

# -----------------------------------------------------------------------------
step "Checking the stack is up"
# -----------------------------------------------------------------------------
for name in "application-service ${APPLICATION_API}" "document-service ${DOCUMENT_API}"; do
    set -- ${name}
    if ! curl -sf "${2}/actuator/health/readiness" | grep -q UP; then
        fail "$1 is not ready at $2. Run 'make local-up' first."
    fi
    ok "$1 is ready"
done

# -----------------------------------------------------------------------------
step "Obtaining a token from the local OIDC issuer"
# -----------------------------------------------------------------------------
# The services validate a real signed token against the issuer's JWKS. Security
# is not disabled locally: a platform whose auth is only ever exercised in
# production is a platform whose auth has never been tested.
#
# The Host header is not a trick for its own sake. The mock issuer derives the
# "iss" claim from the request host, and the services are configured to trust
# "http://oidc-issuer:8080/default" -- the name by which they reach it on the
# Docker network. A token fetched from the host as "localhost:8090" would carry
# a different issuer and be correctly rejected. Sending the header makes the
# issued token identical to one a service would obtain itself.
TOKEN="$(curl -sf -X POST "${ISSUER}/token" \
    -H "Host: ${ISSUER_INTERNAL_HOST}" \
    -d "grant_type=client_credentials" \
    -d "client_id=local-smoke-test" \
    -d "client_secret=local-only-not-a-secret" \
    -d "scope=applications:read applications:write documents:read documents:write" \
    | jq -r '.access_token')"

[ -n "${TOKEN}" ] && [ "${TOKEN}" != "null" ] || fail "Could not obtain a token from ${ISSUER}"
ok "Token obtained"

AUTH=(-H "Authorization: Bearer ${TOKEN}")
JSON=(-H "Content-Type: application/json")

# -----------------------------------------------------------------------------
step "Creating a draft application"
# -----------------------------------------------------------------------------
# Synthetic applicant. The email uses the RFC 2606 reserved example.com domain;
# the name is a placeholder. Nothing here corresponds to a real person.
IDEMPOTENCY_KEY="smoke-$(date +%s)-$$"
CREATE_RESPONSE="$(curl -sf -X POST "${APPLICATION_API}/v1/applications" \
    "${AUTH[@]}" "${JSON[@]}" \
    -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
    -d '{
        "applicant": {
            "givenName": "Smoke",
            "familyName": "Test",
            "emailAddress": "smoke.test@example.com",
            "dateOfBirth": "1990-04-17",
            "residenceCountry": "DE"
        },
        "loan": {
            "amountMinorUnits": 1250000,
            "currency": "EUR",
            "termMonths": 48,
            "purpose": "HOME_IMPROVEMENT",
            "declaredAnnualIncomeMinorUnits": 6000000,
            "productCode": "PL-STD-01"
        }
    }')"

APPLICATION_ID="$(echo "${CREATE_RESPONSE}" | jq -r '.applicationId')"
[ "${APPLICATION_ID}" != "null" ] || fail "Application was not created: ${CREATE_RESPONSE}"
ok "Application ${APPLICATION_ID}"

# The API must not echo the applicant's details back.
if echo "${CREATE_RESPONSE}" | grep -qi "smoke.test@example.com"; then
    fail "The API returned the applicant's email address. It must return a pseudonym only."
fi
ok "Response carries a pseudonym, not personal data"

# -----------------------------------------------------------------------------
step "Verifying idempotency"
# -----------------------------------------------------------------------------
# Byte-identical body, same key. The response must be the original
# application, replayed, rather than a second one.
REPLAY="$(curl -sf -X POST "${APPLICATION_API}/v1/applications" \
    "${AUTH[@]}" "${JSON[@]}" \
    -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
    -d '{
        "applicant": {
            "givenName": "Smoke",
            "familyName": "Test",
            "emailAddress": "smoke.test@example.com",
            "dateOfBirth": "1990-04-17",
            "residenceCountry": "DE"
        },
        "loan": {
            "amountMinorUnits": 1250000,
            "currency": "EUR",
            "termMonths": 48,
            "purpose": "HOME_IMPROVEMENT",
            "declaredAnnualIncomeMinorUnits": 6000000,
            "productCode": "PL-STD-01"
        }
    }')"

REPLAY_ID="$(echo "${REPLAY}" | jq -r '.applicationId')"
[ "${REPLAY_ID}" = "${APPLICATION_ID}" ] || fail "Repeating the idempotency key created a second application"
ok "Repeated key returned the original application"

# -----------------------------------------------------------------------------
step "Uploading the mandatory documents"
# -----------------------------------------------------------------------------
SYNTHETIC_FILE="$(mktemp)"
trap 'rm -f "${SYNTHETIC_FILE}"' EXIT
printf '%%PDF-1.4 synthetic smoke-test document, not a real file\n' > "${SYNTHETIC_FILE}"

for DOCUMENT_TYPE in PROOF_OF_IDENTITY PROOF_OF_INCOME; do
    TICKET="$(curl -sf -X POST \
        "${DOCUMENT_API}/v1/applications/${APPLICATION_ID}/documents/upload-requests" \
        "${AUTH[@]}" "${JSON[@]}" \
        -d "{\"documentType\":\"${DOCUMENT_TYPE}\",\"contentType\":\"application/pdf\"}")"

    DOCUMENT_ID="$(echo "${TICKET}" | jq -r '.documentId')"
    UPLOAD_URL="$(echo "${TICKET}" | jq -r '.uploadUrl')"
    [ "${DOCUMENT_ID}" != "null" ] || fail "No upload slot for ${DOCUMENT_TYPE}: ${TICKET}"

    # Straight to S3. This request never touches the document service: it holds
    # no AWS credential, exactly like a browser would not.
    UPLOAD_STATUS="$(curl -s -o /dev/null -w '%{http_code}' -X PUT "${UPLOAD_URL}" \
        -H "Content-Type: application/pdf" \
        --data-binary "@${SYNTHETIC_FILE}")"
    [ "${UPLOAD_STATUS}" = "200" ] || fail "Direct upload to S3 failed with HTTP ${UPLOAD_STATUS}"

    curl -sf -X POST \
        "${DOCUMENT_API}/v1/applications/${APPLICATION_ID}/documents/${DOCUMENT_ID}/complete" \
        "${AUTH[@]}" "${JSON[@]}" -d '{}' > /dev/null

    ok "${DOCUMENT_TYPE} uploaded directly to S3 and completion recorded"
done

# -----------------------------------------------------------------------------
step "Waiting for the scanner to clear the documents"
# -----------------------------------------------------------------------------
# The scan is asynchronous by design: a client's HTTP request must not wait on
# a malware scanner.
for attempt in $(seq 1 "${MAX_WAIT_SECONDS}"); do
    CLEAN_COUNT="$(curl -sf "${DOCUMENT_API}/v1/applications/${APPLICATION_ID}/documents" "${AUTH[@]}" \
        | jq '[.[] | select(.status == "CLEAN")] | length')"
    [ "${CLEAN_COUNT}" -ge 2 ] && break
    [ "${attempt}" -eq "${MAX_WAIT_SECONDS}" ] && fail "Documents were not cleared within ${MAX_WAIT_SECONDS}s"
    sleep 1
done
ok "Both mandatory documents reached CLEAN"

# -----------------------------------------------------------------------------
step "Submitting the application"
# -----------------------------------------------------------------------------
# The application service learns that the documents are clean from the document
# events, not by calling the document service, so this also proves the Kafka
# path and the local projection are working.
for attempt in $(seq 1 "${MAX_WAIT_SECONDS}"); do
    SUBMIT_STATUS="$(curl -s -o /tmp/los-submit-response.json -w '%{http_code}' \
        -X POST "${APPLICATION_API}/v1/applications/${APPLICATION_ID}/submit" \
        "${AUTH[@]}" -H "Idempotency-Key: submit-${IDEMPOTENCY_KEY}")"

    [ "${SUBMIT_STATUS}" = "202" ] && break

    # 422 means the projection has not caught up yet. Any other status is a real
    # failure and should not be retried into a timeout.
    if [ "${SUBMIT_STATUS}" != "422" ]; then
        fail "Submission failed with HTTP ${SUBMIT_STATUS}: $(cat /tmp/los-submit-response.json)"
    fi
    [ "${attempt}" -eq "${MAX_WAIT_SECONDS}" ] && fail "Submission never became possible within ${MAX_WAIT_SECONDS}s"
    sleep 1
done
ok "Application submitted (202 Accepted)"

# -----------------------------------------------------------------------------
step "Waiting for the assessment to reach a decision"
# -----------------------------------------------------------------------------
# Every check runs through a simulated provider. Nothing external is contacted.
FINAL_STATUS=""
for attempt in $(seq 1 "${MAX_WAIT_SECONDS}"); do
    STATUS_RESPONSE="$(curl -sf "${APPLICATION_API}/v1/applications/${APPLICATION_ID}/status" "${AUTH[@]}")"
    FINAL_STATUS="$(echo "${STATUS_RESPONSE}" | jq -r '.status')"
    TERMINAL="$(echo "${STATUS_RESPONSE}" | jq -r '.terminal')"

    if [ "${TERMINAL}" = "true" ] || [ "${FINAL_STATUS}" = "MANUAL_REVIEW" ]; then
        break
    fi
    [ "${attempt}" -eq "${MAX_WAIT_SECONDS}" ] && fail "No decision within ${MAX_WAIT_SECONDS}s (last status: ${FINAL_STATUS})"
    sleep 1
done

case "${FINAL_STATUS}" in
    APPROVED|REJECTED|MANUAL_REVIEW)
        ok "Assessment reached ${FINAL_STATUS}"
        ;;
    *)
        fail "Assessment ended in an unexpected status: ${FINAL_STATUS}"
        ;;
esac

# -----------------------------------------------------------------------------
step "Confirming the audit trail recorded it"
# -----------------------------------------------------------------------------
# Queried through the database because the audit service exposes no read API:
# it consumes events and writes; nothing may ask it to change anything.
AUDIT_COUNT="$(docker exec los-postgres psql -U los_local -d los -tAc \
    "SELECT count(*) FROM audit.audit_record WHERE chain_key = '${APPLICATION_ID}'" 2>/dev/null || echo 0)"

[ "${AUDIT_COUNT}" -gt 0 ] || fail "The audit trail has no records for this application"
ok "${AUDIT_COUNT} audit records written"

# The chain must link up.
CHAIN_BREAKS="$(docker exec los-postgres psql -U los_local -d los -tAc "
    SELECT count(*) FROM audit.audit_record a
    JOIN audit.audit_record b
      ON b.chain_key = a.chain_key AND b.sequence_number = a.sequence_number - 1
    WHERE a.chain_key = '${APPLICATION_ID}' AND a.previous_hash IS DISTINCT FROM b.record_hash
" 2>/dev/null || echo 1)"

[ "${CHAIN_BREAKS}" = "0" ] || fail "The audit hash chain is broken (${CHAIN_BREAKS} bad links)"
ok "Audit hash chain is intact"

# No personal data may have reached the trail.
LEAKED="$(docker exec los-postgres psql -U los_local -d los -tAc \
    "SELECT count(*) FROM audit.audit_record WHERE summary::text ILIKE '%smoke.test%' OR summary::text ILIKE '%Smoke%'" \
    2>/dev/null || echo 1)"
[ "${LEAKED}" = "0" ] || fail "Personal data reached the audit trail"
ok "No personal data in the audit trail"

# -----------------------------------------------------------------------------
printf '\n%sLocal smoke test passed.%s\n' "${GREEN}" "${RESET}"
printf 'Application %s ended in %s.\n\n' "${APPLICATION_ID}" "${FINAL_STATUS}"
