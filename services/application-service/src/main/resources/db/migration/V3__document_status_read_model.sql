-- =============================================================================
-- Local read model of document status.
--
-- Submission must refuse an application whose mandatory documents have not been
-- scanned clean. There are two ways to know that, and the choice matters:
--
--   1. Call the document service synchronously during submission.
--      Simple, but it puts a second service on the critical path of the
--      platform's most important write. If the document service is slow, every
--      submission is slow; if it is down, no application can be submitted even
--      though the answer is knowable and has not changed in hours.
--
--   2. Consume document events and keep the answer locally.  <-- chosen
--      Submission needs no network call at all. The document service can be
--      redeployed, scaled or broken without blocking submissions.
--
-- The cost of (2) is staleness: this table is eventually consistent with the
-- document context. That is acceptable here because the transition it guards is
-- monotonic in the direction that matters -- a document only becomes CLEAN once,
-- and staleness can only delay a submission, never wrongly permit one. If a
-- scan result has not arrived yet, the submission is refused and the client
-- retries; the platform never approves an application on the strength of a
-- document that was actually rejected.
--
-- This is a projection, not a system of record. The document service owns the
-- truth. Nothing here is ever written by a request; only by the event consumer.
-- =============================================================================

CREATE TABLE application.document_status
(
    document_id    UUID         PRIMARY KEY,
    application_id UUID         NOT NULL,
    document_type  VARCHAR(32)  NOT NULL,
    status         VARCHAR(32)  NOT NULL,

    -- Version of the document aggregate this row reflects. An event carrying an
    -- older version is a redelivery of something already superseded and is
    -- ignored, which is what makes the projection safe under out-of-order
    -- delivery.
    source_version BIGINT       NOT NULL,

    updated_at     TIMESTAMPTZ  NOT NULL,

    CONSTRAINT document_status_known
        CHECK (status IN ('PENDING_UPLOAD', 'UPLOADED', 'SCANNING', 'CLEAN', 'REJECTED'))
);

-- The submission check: "which document types are CLEAN for this application?"
CREATE INDEX idx_document_status_application
    ON application.document_status (application_id, status);

COMMENT ON TABLE application.document_status IS
    'Eventually consistent projection of document status, fed by document events. Not a system of record.';
