-- =============================================================================
-- Application bounded context: system of record for loan applications.
--
-- This service owns the "application" schema and nothing else. No other service
-- reads or writes these tables; they integrate through the API and through
-- events. That rule is enforced by the per-service database roles created in
-- V2 and by the architecture tests.
--
-- Migrations follow expand-and-contract: a release only ever adds nullable
-- columns or new tables, backfills, then removes the old shape in a later
-- release, so a rolling deployment never has a pod running against a schema it
-- cannot read. See docs/operations/database-migrations.md.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS application;

-- -----------------------------------------------------------------------------
-- The aggregate root.
--
-- Applicant columns hold personal data and are the only place on the platform
-- that does. They are protected by RDS encryption at rest under a customer
-- managed KMS key, they are never selected into an event or an audit record, and
-- they are never returned in full by the API.
-- -----------------------------------------------------------------------------
CREATE TABLE application.loan_application
(
    id                                 UUID         PRIMARY KEY,

    -- Irreversible HMAC pseudonym. This is the only applicant identifier that
    -- leaves this service, so it is indexed for correlation queries.
    applicant_reference                VARCHAR(32)  NOT NULL,

    status                             VARCHAR(32)  NOT NULL,

    -- Personal data. Classified RESTRICTED; see docs/security/data-classification.md.
    applicant_given_name               VARCHAR(200) NOT NULL,
    applicant_family_name              VARCHAR(200) NOT NULL,
    applicant_email                    VARCHAR(320) NOT NULL,
    applicant_date_of_birth            DATE         NOT NULL,
    applicant_residence_country        VARCHAR(2)   NOT NULL,

    -- The request. Money is stored as integer minor units, never as a float and
    -- never as NUMERIC with an implicit scale, so no rounding can differ between
    -- the database and the JVM.
    amount_minor_units                 BIGINT       NOT NULL,
    currency                           VARCHAR(3)   NOT NULL,
    term_months                        INTEGER      NOT NULL,
    purpose                            VARCHAR(32)  NOT NULL,
    declared_annual_income_minor_units BIGINT       NOT NULL,
    product_code                       VARCHAR(32)  NOT NULL,

    -- The decision, once one exists.
    decision_type                      VARCHAR(16),
    decision_reason_code               VARCHAR(64),
    decision_decided_by                VARCHAR(64),

    -- Business version of the aggregate: increments once per business
    -- operation and is published on every event so a consumer can detect an
    -- event that arrived out of order.
    aggregate_version                  BIGINT       NOT NULL,

    -- Optimistic lock: counts physical row writes. Deliberately separate from
    -- aggregate_version, which counts business operations.
    row_version                        BIGINT       NOT NULL,

    created_at                         TIMESTAMPTZ  NOT NULL,
    submitted_at                       TIMESTAMPTZ,
    updated_at                         TIMESTAMPTZ  NOT NULL,

    CONSTRAINT loan_application_amount_positive
        CHECK (amount_minor_units > 0),
    CONSTRAINT loan_application_income_non_negative
        CHECK (declared_annual_income_minor_units >= 0),
    CONSTRAINT loan_application_term_range
        CHECK (term_months BETWEEN 6 AND 120),
    CONSTRAINT loan_application_status_known
        CHECK (status IN ('DRAFT', 'SUBMITTED', 'VALIDATING', 'CHECKS_IN_PROGRESS',
                          'MANUAL_REVIEW', 'APPROVED', 'REJECTED', 'FAILED', 'CANCELLED')),
    -- A decision is all-or-nothing: a half-written decision would be
    -- indistinguishable from no decision.
    CONSTRAINT loan_application_decision_complete
        CHECK ((decision_type IS NULL AND decision_reason_code IS NULL AND decision_decided_by IS NULL)
            OR (decision_type IS NOT NULL AND decision_reason_code IS NOT NULL AND decision_decided_by IS NOT NULL)),
    CONSTRAINT loan_application_submitted_at_present
        CHECK (status = 'DRAFT' OR status = 'CANCELLED' OR submitted_at IS NOT NULL)
);

CREATE INDEX idx_loan_application_applicant_reference
    ON application.loan_application (applicant_reference);

-- Supports the manual-review queue and the "stuck in processing" alarm, both of
-- which scan by status ordered by age.
CREATE INDEX idx_loan_application_status_updated_at
    ON application.loan_application (status, updated_at);

COMMENT ON TABLE application.loan_application IS
    'Loan application aggregate. Contains personal data; see docs/security/data-classification.md.';
COMMENT ON COLUMN application.loan_application.applicant_reference IS
    'Irreversible HMAC-SHA-256 pseudonym. The only applicant identifier permitted outside this service.';
COMMENT ON COLUMN application.loan_application.aggregate_version IS
    'Business version, incremented once per business operation. Published on every event.';
COMMENT ON COLUMN application.loan_application.row_version IS
    'JPA optimistic lock version, counting physical writes. Not the business version.';

-- -----------------------------------------------------------------------------
-- Immutable history of the requested terms.
--
-- Holds the request only, never the applicant. A version history that duplicated
-- personal data into an append-only table would multiply the erasure problem by
-- the number of edits.
-- -----------------------------------------------------------------------------
CREATE TABLE application.application_version
(
    application_id                     UUID         NOT NULL,
    version                            BIGINT       NOT NULL,
    status                             VARCHAR(32)  NOT NULL,
    amount_minor_units                 BIGINT       NOT NULL,
    currency                           VARCHAR(3)   NOT NULL,
    term_months                        INTEGER      NOT NULL,
    purpose                            VARCHAR(32)  NOT NULL,
    declared_annual_income_minor_units BIGINT       NOT NULL,
    product_code                       VARCHAR(32)  NOT NULL,
    created_at                         TIMESTAMPTZ  NOT NULL,

    PRIMARY KEY (application_id, version),
    CONSTRAINT fk_application_version_application
        FOREIGN KEY (application_id) REFERENCES application.loan_application (id) ON DELETE CASCADE
);

COMMENT ON TABLE application.application_version IS
    'Append-only history of requested terms. Deliberately free of personal data.';

-- -----------------------------------------------------------------------------
-- Idempotency.
--
-- The primary key is (idempotency_key, operation) so the same key used against
-- two different operations does not collide. request_fingerprint is a SHA-256 of
-- the canonicalised request body: replaying the same key with the SAME body
-- returns the stored response, replaying it with a DIFFERENT body is a conflict.
--
-- The stored response body is the API representation, which by construction
-- contains no personal data.
-- -----------------------------------------------------------------------------
CREATE TABLE application.idempotency_key
(
    idempotency_key     VARCHAR(128) NOT NULL,
    operation           VARCHAR(64)  NOT NULL,
    request_fingerprint VARCHAR(64)  NOT NULL,
    application_id      UUID,
    response_status     INTEGER      NOT NULL,
    response_body       TEXT         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    expires_at          TIMESTAMPTZ  NOT NULL,

    PRIMARY KEY (idempotency_key, operation)
);

-- Supports the scheduled purge of expired keys.
CREATE INDEX idx_idempotency_key_expires_at
    ON application.idempotency_key (expires_at);

COMMENT ON COLUMN application.idempotency_key.request_fingerprint IS
    'SHA-256 of the canonicalised request body. A repeat with a different body is a 409, not a replay.';

-- -----------------------------------------------------------------------------
-- Transactional outbox.
--
-- Events are inserted in the SAME transaction that changes the aggregate, so
-- there is no window in which the state changed but the event was lost, and none
-- in which an event was published for a transaction that then rolled back.
--
-- A separate publisher claims rows with FOR UPDATE SKIP LOCKED and publishes
-- them to Kafka at least once. Consumers are idempotent, which is what makes
-- at-least-once acceptable.
-- -----------------------------------------------------------------------------
CREATE TABLE application.outbox_event
(
    id                UUID         PRIMARY KEY,
    aggregate_type    VARCHAR(64)  NOT NULL,
    aggregate_id      VARCHAR(64)  NOT NULL,
    aggregate_version BIGINT       NOT NULL,
    event_type        VARCHAR(64)  NOT NULL,
    schema_version    INTEGER      NOT NULL,
    topic             VARCHAR(128) NOT NULL,

    -- Kafka partition key. Always the application identifier, so every event
    -- about one application lands on one partition and is delivered in order.
    partition_key     VARCHAR(64)  NOT NULL,

    -- The complete serialised envelope, exactly as it will be published.
    payload           JSONB        NOT NULL,

    correlation_id    VARCHAR(64)  NOT NULL,
    causation_id      VARCHAR(64),

    status            VARCHAR(16)  NOT NULL,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    next_attempt_at   TIMESTAMPTZ  NOT NULL,
    last_error_code   VARCHAR(64),
    created_at        TIMESTAMPTZ  NOT NULL,
    published_at      TIMESTAMPTZ,

    CONSTRAINT outbox_event_status_known
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT outbox_event_published_has_timestamp
        CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

-- The publisher's claim query. Partial index so it stays small no matter how
-- much history accumulates in the table.
CREATE INDEX idx_outbox_event_pending
    ON application.outbox_event (next_attempt_at, created_at)
    WHERE status = 'PENDING';

-- Supports the "oldest unpublished outbox record" alarm.
CREATE INDEX idx_outbox_event_created_at
    ON application.outbox_event (created_at)
    WHERE status <> 'PUBLISHED';

COMMENT ON TABLE application.outbox_event IS
    'Transactional outbox. Written in the same transaction as the aggregate change.';

-- -----------------------------------------------------------------------------
-- Inbox / processed events.
--
-- Kafka delivers at least once, so this service will see duplicates. The unique
-- primary key is what makes a duplicate a no-op: a consumer inserts the event
-- identifier in the same transaction as its business effect, and a duplicate
-- fails that insert instead of applying the effect twice.
-- -----------------------------------------------------------------------------
CREATE TABLE application.processed_event
(
    event_id     VARCHAR(64) NOT NULL,
    consumer     VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (event_id, consumer)
);

COMMENT ON TABLE application.processed_event IS
    'De-duplication ledger. The primary key is the idempotency guarantee for consumers.';
