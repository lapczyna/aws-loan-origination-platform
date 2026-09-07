#!/usr/bin/env bash
# =============================================================================
# Creates the platform's Kafka topics, then exits.
#
# WHY THIS IS NOT DONE BY THE APPLICATIONS
# ----------------------------------------
# Automatic topic creation is disabled on the broker here exactly as it is on
# Amazon MSK. A service that can create a topic can create the WRONG topic: a
# typo in a topic name produces an empty topic nobody is reading, with the
# default partition count, and nothing fails until somebody asks why messages
# are missing. Creating them deliberately means a typo is a startup failure.
#
# The partition counts below are the local equivalents of what the Terraform MSK
# module provisions. Partition count is effectively permanent -- increasing it
# changes which partition a key hashes to, breaking per-key ordering for every
# key already in flight -- so it is a decision made once, here and in Terraform
# together.
# =============================================================================

set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
KAFKA_TOPICS="/opt/kafka/bin/kafka-topics.sh"

echo "Waiting for the broker at ${BOOTSTRAP}..."
for attempt in $(seq 1 60); do
    if /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server "${BOOTSTRAP}" >/dev/null 2>&1; then
        break
    fi
    if [ "${attempt}" -eq 60 ]; then
        echo "Broker did not become reachable in time." >&2
        exit 1
    fi
    sleep 2
done

create_topic() {
    local name="$1"
    local partitions="$2"
    local retention_ms="$3"
    local description="$4"

    echo "  ${name} (${partitions} partitions) - ${description}"

    # --if-not-exists so the script is idempotent: `make local-up` on an existing
    # stack must not fail because the topics are already there.
    "${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP}" \
        --create --if-not-exists \
        --topic "${name}" \
        --partitions "${partitions}" \
        --replication-factor 1 \
        --config "retention.ms=${retention_ms}" \
        --config "min.insync.replicas=1" \
        --config "cleanup.policy=delete" \
        > /dev/null
}

echo "Creating topics..."

# Six partitions on the business topics: enough for a handful of consumer
# instances without over-partitioning a laptop. Ordering is per application
# because applicationId is always the partition key, so partition count does not
# affect correctness -- only how far the consumers can scale out.
#
# Retention is 7 days locally. Production retains far longer; the durable record
# is PostgreSQL and the audit trail, not the topic.
create_topic "los.application.events.v1" 6 604800000 "loan application facts"
create_topic "los.document.events.v1"    6 604800000 "document upload and scan results"
create_topic "los.workflow.events.v1"    6 604800000 "assessment progress and outcomes"

# One partition on the dead-letter topic. It should be empty; ordering matters
# more than throughput when a human is reading it during an incident.
create_topic "los.dlq.v1" 1 2592000000 "events that exhausted their retry budget"

echo ""
echo "Topics ready:"
"${KAFKA_TOPICS}" --bootstrap-server "${BOOTSTRAP}" --list | sed 's/^/  /'
