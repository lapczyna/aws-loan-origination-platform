-- =============================================================================
-- Document bounded context: metadata and the quarantine lifecycle.
--
-- This service owns the "document" schema. It stores metadata ONLY. File bytes
-- never pass through it: clients upload directly to S3 using short-lived
-- presigned URLs, and the service never reads or writes object content.
--
-- That is a deliberate architectural constraint, not a convenience. Proxying
-- uploads would put every megabyte of every document through a JVM heap and
-- through API Gateway, which has a hard payload limit, turning a scaling problem
-- and a cost problem into a correctness problem the first time someone uploads a
-- large scan.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS document;

-- -----------------------------------------------------------------------------
-- Document metadata.
--
-- Contains no personal data and no file content. The object key is built from
-- opaque identifiers only: a key containing an applicant's name would leak it
-- through S3 access logs, CloudTrail, bucket inventory reports and every
-- presigned URL ever issued.
-- -----------------------------------------------------------------------------
CREATE TABLE document.document
(
    id                UUID         PRIMARY KEY,
    application_id    UUID         NOT NULL,
    document_type     VARCHAR(32)  NOT NULL,
    status            VARCHAR(32)  NOT NULL,

    -- S3 location. The bucket is configuration rather than a hard-coded value so
    -- the same image runs against a local LocalStack bucket and a real one.
    bucket            VARCHAR(255) NOT NULL,
    object_key        VARCHAR(512) NOT NULL,

    -- What the client declared when requesting the upload, and what S3 actually
    -- reported afterwards. Both are kept: a mismatch is exactly the signal that
    -- something other than the intended file was uploaded.
    declared_content_type VARCHAR(128) NOT NULL,
    declared_size_bytes   BIGINT,
    stored_size_bytes     BIGINT,
    stored_etag           VARCHAR(128),
    stored_checksum_sha256 VARCHAR(64),

    scan_reason_code  VARCHAR(64),

    aggregate_version BIGINT       NOT NULL,
    row_version       BIGINT       NOT NULL,

    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,

    -- When the presigned URL stops working. Kept so the abandoned-upload sweep
    -- can tell "still waiting" from "never arrived".
    upload_expires_at TIMESTAMPTZ  NOT NULL,

    CONSTRAINT document_status_known
        CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'SCANNING', 'CLEAN', 'REJECTED')),
    CONSTRAINT document_type_known
        CHECK (document_type IN ('PROOF_OF_IDENTITY', 'PROOF_OF_INCOME', 'PROOF_OF_ADDRESS', 'BANK_STATEMENT')),
    CONSTRAINT document_sizes_non_negative
        CHECK ((declared_size_bytes IS NULL OR declared_size_bytes >= 0)
           AND (stored_size_bytes IS NULL OR stored_size_bytes >= 0)),
    -- A document that has been stored must have a recorded size, or the size
    -- limit was never actually enforced against what arrived.
    CONSTRAINT document_stored_has_size
        CHECK (status IN ('PENDING_UPLOAD', 'REJECTED') OR stored_size_bytes IS NOT NULL),
    -- The object key must be unique: two documents sharing one key would let a
    -- second upload silently replace the first one's scan result.
    CONSTRAINT document_object_key_unique UNIQUE (bucket, object_key)
);

-- The submission check: "which document types are CLEAN for this application?"
CREATE INDEX idx_document_application_status
    ON document.document (application_id, status);

-- Backs the sweep for uploads that were requested and never completed.
CREATE INDEX idx_document_abandoned
    ON document.document (upload_expires_at)
    WHERE status = 'PENDING_UPLOAD';

COMMENT ON TABLE document.document IS
    'Document metadata only. No file content, no personal data. Object keys contain opaque identifiers exclusively.';
COMMENT ON COLUMN document.document.object_key IS
    'S3 key built from opaque identifiers. Never contains an applicant name, email address or national identifier: keys appear in access logs, CloudTrail and inventory reports.';
COMMENT ON COLUMN document.document.stored_checksum_sha256 IS
    'Checksum reported by S3 after upload. Compared against the value the client declared, so a client cannot claim to have uploaded one file and actually upload another.';

-- -----------------------------------------------------------------------------
-- Transactional outbox and de-duplication ledger, as in every other context.
-- -----------------------------------------------------------------------------
CREATE TABLE document.outbox_event
(
    id                UUID         PRIMARY KEY,
    aggregate_type    VARCHAR(64)  NOT NULL,
    aggregate_id      VARCHAR(64)  NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    event_type        VARCHAR(64)  NOT NULL,
    schema_version    INTEGER      NOT NULL,
    topic             VARCHAR(128) NOT NULL,
    partition_key     VARCHAR(64)  NOT NULL,
    payload           JSONB        NOT NULL,
    correlation_id    VARCHAR(64)  NOT NULL,
    causation_id      VARCHAR(64),
    status            VARCHAR(16)  NOT NULL,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL,
    last_error_code   VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL,
    published_at      TIMESTAMPTZ,

    CONSTRAINT document_outbox_status_known
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT document_outbox_published_has_timestamp
        CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE INDEX idx_document_outbox_pending
    ON document.outbox_event (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_document_outbox_created_at
    ON document.outbox_event (created_at)
    WHERE status <> 'PUBLISHED';

CREATE TABLE document.processed_event
(
    event_id     VARCHAR(64) NOT NULL,
    consumer     VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (event_id, consumer)
);
