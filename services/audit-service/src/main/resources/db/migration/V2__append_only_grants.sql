-- =============================================================================
-- Append-only enforcement for the audit runtime role.
--
-- The audit trail's value rests entirely on the claim that it cannot be quietly
-- edited. A comment saying "append-only" does not establish that; a grant does.
--
-- The runtime role is given SELECT and INSERT on the audit table and nothing
-- else. It has no UPDATE and no DELETE, so a defect, a compromised pod or a
-- well-meaning operator using the application credential cannot rewrite history.
-- Combined with the hash chain, an attacker who does obtain a stronger
-- credential still cannot alter a record without breaking every hash after it.
--
-- KNOWN LIMITATION -- role bootstrapping
-- --------------------------------------
-- Flyway runs as the migrator, which deliberately is not a superuser and so
-- cannot create roles. The roles are created outside this migration:
--   * locally   by deploy/compose/postgres-init/01-create-roles.sh
--   * on RDS    by a one-time bootstrap using the AWS-managed master credential
--               (see docs/operations/database-bootstrap.md)
-- This migration is a no-op when the runtime role does not exist, so a developer
-- machine without the bootstrap still gets a working database.
-- =============================================================================

DO
$$
    DECLARE
        runtime_role CONSTANT TEXT := 'los_audit_runtime';
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
            RAISE NOTICE
                'Role % does not exist; skipping grants. Expected on a developer machine without the role bootstrap.',
                runtime_role;
            RETURN;
        END IF;

        EXECUTE format('GRANT USAGE ON SCHEMA audit TO %I', runtime_role);

        -- Read and append only. Deliberately no UPDATE and no DELETE.
        EXECUTE format('GRANT SELECT, INSERT ON audit.audit_record TO %I', runtime_role);

        -- The de-duplication ledger is ordinary working data, not part of the
        -- audit trail, so it may be pruned.
        EXECUTE format('GRANT SELECT, INSERT, DELETE ON audit.processed_event TO %I', runtime_role);

        EXECUTE format('REVOKE CREATE ON SCHEMA audit FROM %I', runtime_role);

        RAISE NOTICE 'Granted append-only access on schema audit to %', runtime_role;
    END
$$;
