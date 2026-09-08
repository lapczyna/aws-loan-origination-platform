# Bootstrapping the database

> **This has never been run.** No RDS instance exists.

---

## The chicken and egg

The services authenticate to PostgreSQL with **IAM database authentication**:
the pod's IAM role is the credential, so there is no password to rotate, leak or
accidentally log.

But an IAM database user has to be *created* first, and creating it needs a
password-authenticated connection. So there is exactly one password, used once,
at bootstrap.

## The master password is never in Terraform

`manage_master_user_password = true`. AWS generates the password, stores it in
Secrets Manager, and rotates it. No password passes through Terraform, so none
appears in the configuration, in the plan output, or **in the state file**.

Retrieve it only when bootstrapping, from a host that can reach the database:

```bash
aws secretsmanager get-secret-value \
    --secret-id "$(aws rds describe-db-instances \
        --db-instance-identifier "${DB_IDENTIFIER}" \
        --query 'DBInstances[0].MasterUserSecret.SecretArn' --output text)" \
    --query SecretString --output text
```

The database has no public route, so this runs from a bastion or a task inside
the VPC — never from a laptop over the internet.

## Roles, and why there are two per service

Each service uses **two** database identities:

| Role | Used by | Privileges |
|---|---|---|
| `los_<context>_migrator` | Flyway, at startup | `CREATE`, `ALTER`, `DROP` within its own schema |
| `los_<context>_runtime` | The running service | `SELECT`, `INSERT`, `UPDATE`, `DELETE` on its own tables — and nothing else |

Splitting them means a compromised running service cannot alter the schema, and
in the audit context it is what makes the append-only guarantee real: the audit
runtime role holds `SELECT, INSERT` **only**, so PostgreSQL itself refuses an
`UPDATE` or a `DELETE` on `audit.audit_record`. That has been verified against a
real database.

## Sketch

```sql
-- As the master user, once per service.

CREATE ROLE los_application_migrator LOGIN;
CREATE ROLE los_application_runtime LOGIN;

-- IAM authentication rather than passwords, for both.
GRANT rds_iam TO los_application_migrator;
GRANT rds_iam TO los_application_runtime;

CREATE SCHEMA IF NOT EXISTS application AUTHORIZATION los_application_migrator;

-- The runtime role may use the schema but not change it.
GRANT USAGE ON SCHEMA application TO los_application_runtime;

-- Applies to tables Flyway creates LATER, which is the part that is easy to
-- forget: without it, every new migration needs a manual grant.
ALTER DEFAULT PRIVILEGES FOR ROLE los_application_migrator IN SCHEMA application
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO los_application_runtime;

ALTER DEFAULT PRIVILEGES FOR ROLE los_application_migrator IN SCHEMA application
    GRANT USAGE, SELECT ON SEQUENCES TO los_application_runtime;

-- Nobody connects as the public role.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
```

The audit context is the exception, and deliberately narrower — it is applied by
`V2__append_only_grants.sql` rather than here, so the guarantee lives with the
schema that depends on it:

```sql
GRANT SELECT, INSERT ON audit.audit_record TO los_audit_runtime;
-- No UPDATE. No DELETE. Not an oversight.
```

## Then map IAM roles to database users

Each service's IRSA role needs `rds-db:connect` on
`arn:aws:rds-db:<region>:<account>:dbuser:<resource-id>/<db-user>`, scoped to
that one database user. A role that can connect as any user is not least
privilege.

## Afterwards

- **Do not keep the master password anywhere.** It is in Secrets Manager, it
  rotates, and it should not be needed again outside another bootstrap.
- Confirm each service can connect **without** a password before considering the
  bootstrap done.
- `rds.force_ssl = 1` is set in the parameter group, so a client cannot silently
  negotiate an unencrypted connection. Confirm connections are actually TLS.

## Related

[`runbooks/rds-saturation.md`](runbooks/rds-saturation.md) ·
[`runbooks/secret-rotation.md`](runbooks/secret-rotation.md)
