-- =============================================================================
-- Workflow bounded context: durable assessment state.
--
-- This service owns the "workflow" schema. It never reads the application
-- service's tables; everything it needs arrives on an event and is copied here,
-- which is what lets an assessment run while the application service is down.
--
-- The whole point of these tables is that assessment progress survives a
-- process restart. A workflow engine that kept its progress on a thread's stack
-- would lose it on every deployment and would have to re-run external checks
-- that already succeeded -- against providers that charge per call.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS workflow;

-- -----------------------------------------------------------------------------
-- One assessment of one application.
--
-- Holds no personal data. The applicant is identified only by the pseudonymous
-- reference produced by the application service, and the risk inputs are figures
-- rather than attributes of a person.
-- -----------------------------------------------------------------------------
CREATE TABLE workflow.workflow_instance
(
    id                                 UUID         PRIMARY KEY,

    -- One assessment per application. The unique constraint is the last line of
    -- defence against a duplicated submission event starting a second
    -- assessment; the de-duplication ledger is the first.
    application_id                     UUID         NOT NULL UNIQUE,

    applicant_reference                VARCHAR(32)  NOT NULL,
    status                             VARCHAR(16)  NOT NULL,

    -- Risk inputs, copied from the submission event.
    amount_minor_units                 BIGINT       NOT NULL,
    currency                           VARCHAR(3)   NOT NULL,
    term_months                        INTEGER      NOT NULL,
    purpose                            VARCHAR(32)  NOT NULL,
    declared_annual_income_minor_units BIGINT       NOT NULL,
    product_code                       VARCHAR(32)  NOT NULL,

    recommendation                     VARCHAR(16),
    reason_code                        VARCHAR(64),

    aggregate_version                  BIGINT       NOT NULL,
    row_version                        BIGINT       NOT NULL,

    started_at                         TIMESTAMPTZ  NOT NULL,
    completed_at                       TIMESTAMPTZ,

    -- When any check is next due. Denormalised from workflow_check so the
    -- scheduler can find due workflows with one indexed scan instead of a join
    -- and an aggregate over every check row in the system.
    next_attempt_at                    TIMESTAMPTZ,

    CONSTRAINT workflow_status_known
        CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT workflow_recommendation_known
        CHECK (recommendation IS NULL OR recommendation IN ('APPROVE', 'REJECT', 'MANUAL_REVIEW')),
    CONSTRAINT workflow_completed_has_timestamp
        CHECK (status = 'RUNNING' OR completed_at IS NOT NULL),
    -- A completed assessment must have produced a recommendation. A FAILED one
    -- must not: nothing was decided about the applicant.
    CONSTRAINT workflow_completed_has_recommendation
        CHECK ((status = 'COMPLETED' AND recommendation IS NOT NULL)
            OR (status <> 'COMPLETED' AND recommendation IS NULL))
);

-- The scheduler's claim query. Partial, so it stays small as completed
-- assessments accumulate.
CREATE INDEX idx_workflow_instance_due
    ON workflow.workflow_instance (next_attempt_at)
    WHERE status = 'RUNNING';

-- Backs the "assessments stuck in progress" alarm.
CREATE INDEX idx_workflow_instance_running_started
    ON workflow.workflow_instance (started_at)
    WHERE status = 'RUNNING';

COMMENT ON TABLE workflow.workflow_instance IS
    'Durable assessment state. Contains no personal data: the applicant is a pseudonym.';
COMMENT ON COLUMN workflow.workflow_instance.next_attempt_at IS
    'Earliest due time across this workflow''s checks. Denormalised so the scheduler needs no join.';

-- -----------------------------------------------------------------------------
-- The durable state of one check within one assessment.
--
-- Retry state lives here rather than in memory, which is what lets a workflow
-- resume mid-backoff after a restart instead of starting its retry schedule
-- again from zero.
-- -----------------------------------------------------------------------------
CREATE TABLE workflow.workflow_check
(
    workflow_id     UUID         NOT NULL,
    check_type      VARCHAR(16)  NOT NULL,

    outcome         VARCHAR(16),
    reason_code     VARCHAR(64),
    score           INTEGER,

    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64),
    next_attempt_at TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,

    PRIMARY KEY (workflow_id, check_type),
    CONSTRAINT fk_workflow_check_instance
        FOREIGN KEY (workflow_id) REFERENCES workflow.workflow_instance (id) ON DELETE CASCADE,
    CONSTRAINT workflow_check_type_known
        CHECK (check_type IN ('KYC', 'AML', 'FRAUD', 'CREDIT_SCORE')),
    CONSTRAINT workflow_check_outcome_known
        CHECK (outcome IS NULL OR outcome IN ('PASSED', 'FAILED', 'INCONCLUSIVE')),
    CONSTRAINT workflow_check_score_range
        CHECK (score IS NULL OR score BETWEEN 0 AND 1000)
);

COMMENT ON COLUMN workflow.workflow_check.reason_code IS
    'Stable reason code. The sentinel ABANDONED marks a check that exhausted its retry budget, which is deliberately NOT an outcome: "the bureau was down" must never be read as "the applicant failed".';

-- -----------------------------------------------------------------------------
-- Transactional outbox. Same guarantee as in the application context: a
-- recorded decision cannot fail to be announced, and an announcement can never
-- describe a decision that rolled back.
-- -----------------------------------------------------------------------------
CREATE TABLE workflow.outbox_event
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

    CONSTRAINT workflow_outbox_status_known
        CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT workflow_outbox_published_has_timestamp
        CHECK (status <> 'PUBLISHED' OR published_at IS NOT NULL)
);

CREATE INDEX idx_workflow_outbox_pending
    ON workflow.outbox_event (next_attempt_at, created_at)
    WHERE status = 'PENDING';

CREATE INDEX idx_workflow_outbox_created_at
    ON workflow.outbox_event (created_at)
    WHERE status <> 'PUBLISHED';

-- -----------------------------------------------------------------------------
-- De-duplication ledger. The primary key is the idempotency guarantee: a
-- redelivered submission event cannot start a second assessment.
-- -----------------------------------------------------------------------------
CREATE TABLE workflow.processed_event
(
    event_id     VARCHAR(64) NOT NULL,
    consumer     VARCHAR(64) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL,

    PRIMARY KEY (event_id, consumer)
);
