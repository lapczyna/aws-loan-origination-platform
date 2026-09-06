-- =============================================================================
-- Least-privilege grants for the application runtime role.
--
-- Two distinct database roles are used:
--
--   los_application_migrator  runs Flyway. Owns the schema. Can create, alter
--                             and drop objects. Used only during deployment.
--   los_application_runtime   runs the service. Can read and write rows in the
--                             tables it owns. Cannot alter the schema, cannot
--                             drop a table, and cannot read another service's
--                             schema.
--
-- The separation matters because the runtime credential is the one exposed to
-- the internet-facing request path. A SQL injection defect against a role that
-- cannot DROP or ALTER is a data problem; against the migrator role it is a
-- destroyed database.
--
-- KNOWN LIMITATION -- role bootstrapping
-- --------------------------------------
-- Flyway runs as the migrator, and the migrator cannot create roles unless it
-- is a superuser, which it deliberately is not. The two roles are therefore
-- created outside this migration:
--   * locally   by deploy/compose/postgres-init/01-create-roles.sh
--   * on RDS    by a one-time bootstrap step run with the RDS master credential
--               that AWS manages in Secrets Manager
--               (see docs/operations/database-bootstrap.md)
--
-- This migration is written to be a no-op when the runtime role does not exist,
-- so a developer who has not run the bootstrap still gets a working database.
-- =============================================================================

DO
$$
    DECLARE
        runtime_role CONSTANT TEXT := 'los_application_runtime';
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = runtime_role) THEN
            RAISE NOTICE
                'Role % does not exist; skipping grants. This is expected on a developer machine that has not run the role bootstrap.',
                runtime_role;
            RETURN;
        END IF;

        EXECUTE format('GRANT USAGE ON SCHEMA application TO %I', runtime_role);

        -- Row-level data access only. No CREATE, no TRUNCATE, no REFERENCES.
        EXECUTE format(
                'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA application TO %I',
                runtime_role);

        -- Applies the same grant to tables added by later migrations, so a new
        -- table is never accidentally unreadable by the running service.
        EXECUTE format(
                'ALTER DEFAULT PRIVILEGES IN SCHEMA application GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
                runtime_role);

        -- The runtime role must never be able to change the shape of the schema.
        EXECUTE format('REVOKE CREATE ON SCHEMA application FROM %I', runtime_role);

        RAISE NOTICE 'Granted least-privilege data access on schema application to %', runtime_role;
    END
$$;
