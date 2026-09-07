-- =============================================================================
-- Audit bounded context: an append-only, tamper-evident record of what the
-- platform did.
--
-- Two properties define this schema.
--
-- APPEND-ONLY. Rows are inserted and never updated or deleted. That is enforced
-- by the runtime role's grants (V2), not merely by convention: an audit trail the
-- application can rewrite is not an audit trail.
--
-- TAMPER-EVIDENT. Each record carries the hash of the previous record in its
-- sequence, so the rows form a chain. Altering or removing any record breaks
-- every hash after it, and the break is detectable by anyone who can read the
-- table. This does not PREVENT tampering -- someone with database ownership can
-- rewrite anything -- but it makes silent tampering impossible, which is the
-- property an auditor actually needs.
--
-- WHAT IS RECORDED. Pseudonymous identifiers, event types, reason codes and
-- timestamps. Never a request body, never document content, never an applicant's
-- name, email address or date of birth. An audit store is the longest-lived,
-- most widely replicated and least frequently reviewed data on the platform;
-- personal data placed here is personal data nobody will ever successfully erase.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE audit.audit_record
(
    -- Monotonic sequence within a chain. Also the chain's ordering: hashes are
    -- computed over the previous record's hash in sequence order.
    sequence_number    BIGINT       NOT NULL,

    -- Chains are partitioned by subject so that one application's history can be
    -- verified without reading every record the platform has ever written.
    chain_key          VARCHAR(64)  NOT NULL,

    id                 UUID         NOT NULL,

    -- The event that produced this record. Kept so a record can be traced back
    -- to the exact message, and so a replay cannot silently double-write.
    source_event_id    VARCHAR(64)  NOT NULL,
    event_type         VARCHAR(64)  NOT NULL,
    schema_version     INTEGER      NOT NULL,

    aggregate_type     VARCHAR(64)  NOT NULL,
    aggregate_id       VARCHAR(64)  NOT NULL,
    aggregate_version  BIGINT       NOT NULL,

    -- Pseudonymous only. Never a name, an email address or a national identifier.
    subject_reference  VARCHAR(64),

    -- Safe, structured summary: reason codes, statuses, decisions. Never a
    -- request body and never free text a user supplied.
    summary            JSONB        NOT NULL,

    correlation_id     VARCHAR(64)  NOT NULL,
    occurred_at        TIMESTAMPTZ  NOT NULL,
    recorded_at        TIMESTAMPTZ  NOT NULL,

    -- The chain. previous_hash is NULL only for the first record in a chain.
    previous_hash      VARCHAR(64),
    record_hash        VARCHAR(64)  NOT NULL,

    PRIMARY KEY (chain_key, sequence_number),

    -- One record per source event per chain. This is what makes the consumer
    -- idempotent: a redelivered event violates the constraint instead of
    -- appending a second, contradictory record.
    CONSTRAINT audit_record_source_event_unique UNIQUE (chain_key, source_event_id),

    CONSTRAINT audit_record_sequence_positive CHECK (sequence_number > 0),
    -- Only the first record in a chain may have no predecessor.
    CONSTRAINT audit_record_chain_start
        CHECK ((sequence_number = 1 AND previous_hash IS NULL)
            OR (sequence_number > 1 AND previous_hash IS NOT NULL))
);

CREATE INDEX idx_audit_record_aggregate
    ON audit.audit_record (aggregate_id, occurred_at);

CREATE INDEX idx_audit_record_correlation
    ON audit.audit_record (correlation_id);

-- Supports retention reporting and the export to the S3 audit bucket.
CREATE INDEX idx_audit_record_recorded_at
    ON audit.audit_record (recorded_at);

COMMENT ON TABLE audit.audit_record IS
    'Append-only, hash-chained audit trail. Pseudonymous identifiers and safe metadata only: no request bodies, no document content, no personal data.';
COMMENT ON COLUMN audit.audit_record.record_hash IS
    'SHA-256 over this record''s canonical fields and the previous record''s hash. Breaking the chain is detectable; it is not prevented.';
COMMENT ON COLUMN audit.audit_record.summary IS
    'Structured, allow-listed summary. Never a request body and never free text supplied by a user.';

-- -----------------------------------------------------------------------------
-- De-duplication ledger.
--
-- Belt and braces alongside the unique constraint above: the ledger short-
-- circuits a duplicate before any hash is computed, and the constraint catches
-- anything that races past it.
-- -----------------------------------------------------------------------------
CREATE TABLE audit.processed_event
(
    event_id     VARCHAR(64) NOT NULL,
    consumer     VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (event_id, consumer)
);
