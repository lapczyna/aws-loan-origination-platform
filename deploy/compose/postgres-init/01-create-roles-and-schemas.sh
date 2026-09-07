#!/usr/bin/env bash
# =============================================================================
# Creates the per-service database roles.
#
# WHY THIS EXISTS
# ---------------
# Each service's Flyway migrations grant least-privilege access to a runtime
# role, and each of those migrations is written to NO-OP when the role does not
# exist. Without this script the grants would silently never apply, and the
# platform would appear to enforce separation while every service ran as the
# owner. This script is what makes the local environment actually exercise the
# security model rather than only document it.
#
# In AWS the equivalent bootstrap runs once against a new RDS instance using the
# master credential that AWS manages in Secrets Manager. See
# docs/operations/database-bootstrap.md.
#
# ALL CREDENTIALS HERE ARE SYNTHETIC AND LOCAL-ONLY. They work against a
# throwaway container and nowhere else.
# =============================================================================

set -euo pipefail

echo "Creating per-service database roles and schemas..."

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-'EOSQL'
    -- -------------------------------------------------------------------------
    -- Two roles per bounded context.
    --
    --   *_migrator  owns the schema; runs Flyway; may create, alter and drop.
    --   *_runtime   runs the service; may read and write rows and nothing else.
    --
    -- The separation matters because the runtime credential is the one exposed
    -- to the internet-facing request path. A SQL injection defect against a role
    -- that cannot DROP or ALTER is a data problem; against the owner it is a
    -- destroyed database.
    --
    -- Passwords are synthetic and local-only. In AWS the runtime connection uses
    -- IAM database authentication and there is no password at all.
    -- -------------------------------------------------------------------------

    DO $$
    DECLARE
        context TEXT;
        contexts TEXT[] := ARRAY['application', 'document', 'workflow', 'audit'];
    BEGIN
        FOREACH context IN ARRAY contexts LOOP
            -- Migrator: owns the schema.
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'los_' || context || '_migrator') THEN
                EXECUTE format(
                    'CREATE ROLE %I LOGIN PASSWORD %L',
                    'los_' || context || '_migrator',
                    'local-only-migrator-' || context);
            END IF;

            -- Runtime: rows only.
            IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'los_' || context || '_runtime') THEN
                EXECUTE format(
                    'CREATE ROLE %I LOGIN PASSWORD %L',
                    'los_' || context || '_runtime',
                    'local-only-runtime-' || context);
            END IF;

            -- The schema itself is created by each service's first migration.
            -- Creating it here would let Flyway run against a schema it does not
            -- own, and the baseline check exists precisely to catch that.
            RAISE NOTICE 'Roles ready for context: %', context;
        END LOOP;
    END
    $$;

    -- -------------------------------------------------------------------------
    -- The local application user runs the migrations, so it must be able to
    -- create schemas. In AWS the migrator roles do this and the runtime user
    -- never can.
    -- -------------------------------------------------------------------------
    GRANT CREATE ON DATABASE los TO los_local;
EOSQL

echo "Database roles created. Each service's migrations will apply their own least-privilege grants."
