#!/usr/bin/env bash
# =============================================================================
# Container readiness probe.
#
# WHY THIS EXISTS RATHER THAN `curl`
# ----------------------------------
# The runtime image deliberately contains no HTTP client: no curl, no wget, no
# netcat. That is not an oversight. Every binary in a runtime image is a tool an
# attacker who gets code execution can use, and an HTTP client is the single
# most useful one for pulling in a second stage or exfiltrating data.
#
# Kubernetes does not need one -- the kubelet performs HTTP probes from outside
# the container. Docker Compose does: its healthchecks run INSIDE. Bash's
# /dev/tcp redirection gives a real HTTP request with no additional binary, so
# Compose gets a genuine readiness signal and the image stays clean.
#
# Exit 0 means ready. Any other exit means not ready.
# =============================================================================

set -uo pipefail

PORT="${1:-${SERVER_PORT:-8080}}"
PATH_TO_CHECK="${2:-/actuator/health/readiness}"

# Bash opens a TCP connection on file descriptor 3. A failure here means the
# port is not listening yet, which is a legitimate "not ready".
exec 3<>"/dev/tcp/127.0.0.1/${PORT}" 2>/dev/null || exit 1

printf 'GET %s HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n' "${PATH_TO_CHECK}" >&3 || exit 1

# A 200 alone is not enough: the readiness endpoint answers 503 with a body when
# a dependency is down, so the status in the body is what actually matters.
RESPONSE="$(timeout 5 cat <&3)" || exit 1
exec 3<&-

case "${RESPONSE}" in
    *'"status":"UP"'*) exit 0 ;;
    *) exit 1 ;;
esac
