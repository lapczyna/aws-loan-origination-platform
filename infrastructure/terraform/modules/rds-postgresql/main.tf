# =============================================================================
# Amazon RDS for PostgreSQL.
#
# RDS, not Aurora. Aurora is the better choice at high write throughput or when
# fast cloning matters; neither applies here, and RDS is cheaper, simpler to
# reason about and easier to move off. The decision is recorded in
# docs/adr/ADR-0002.
#
# HIGH AVAILABILITY AND DISASTER RECOVERY ARE DIFFERENT THINGS
# ------------------------------------------------------------
# They are conflated often enough that it is worth stating plainly:
#
#   Multi-AZ standby (HA)          Cross-region read replica (DR)
#   -------------------------      ------------------------------
#   SYNCHRONOUS replication        ASYNCHRONOUS replication
#   Zero data loss on failover     Data loss bounded by replica lag
#   Automatic failover, ~1-2 min   Manual promotion, tested runbook
#   Same region                    Another region
#   Not readable                   Readable, and it lags
#
# The standby is NOT a backup: it replicates a mistaken DELETE just as
# faithfully as a legitimate one. Neither is the replica. Backups and
# point-in-time recovery are what protect against a mistake; these protect
# against infrastructure failure.
#
# NOTHING HERE HAS BEEN APPLIED.
# =============================================================================

locals {
  identifier = "${var.environment}-${var.name}"

  common_tags = merge(var.tags, {
    Module = "rds-postgresql"
  })
}

# -----------------------------------------------------------------------------
# Subnet group. Private subnets only.
# -----------------------------------------------------------------------------
resource "aws_db_subnet_group" "this" {
  name       = local.identifier
  subnet_ids = var.private_subnet_ids

  description = "Private subnets for ${local.identifier}. The database has no public route."

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# Security group.
#
# Ingress only from the security groups that are allowed to connect -- never a
# CIDR. A CIDR rule grants access to whatever happens to hold an address in that
# range now or later; a security-group rule follows the workload.
# -----------------------------------------------------------------------------
resource "aws_security_group" "this" {
  name        = "${local.identifier}-db"
  description = "Database access for ${local.identifier}"
  vpc_id      = var.vpc_id

  tags = merge(local.common_tags, { Name = "${local.identifier}-db" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "postgres" {
  for_each = toset(var.allowed_security_group_ids)

  security_group_id            = aws_security_group.this.id
  referenced_security_group_id = each.value
  from_port                    = 5432
  to_port                      = 5432
  ip_protocol                  = "tcp"
  description                  = "PostgreSQL from an explicitly permitted workload"

  tags = local.common_tags
}

# No egress rules at all. A managed database has no legitimate reason to
# initiate an outbound connection, and the absence of egress removes a
# convenient exfiltration path if the instance is ever compromised.

# -----------------------------------------------------------------------------
# Parameter group.
#
# Settings that are security or observability decisions rather than tuning.
# -----------------------------------------------------------------------------
resource "aws_db_parameter_group" "this" {
  name        = "${local.identifier}-pg"
  family      = var.parameter_group_family
  description = "Parameters for ${local.identifier}"

  # TLS is mandatory. Without this a client can silently negotiate an
  # unencrypted connection and nothing reports it.
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }

  # Log statements slower than this. NOT all statements: full statement logging
  # writes bound parameters, and for this platform that means applicant names
  # and email addresses in CloudWatch Logs.
  parameter {
    name  = "log_min_duration_statement"
    value = tostring(var.slow_query_threshold_ms)
  }

  # Log every connection and disconnection: cheap, and the first thing anyone
  # asks for during an incident.
  parameter {
    name  = "log_connections"
    value = "1"
  }

  parameter {
    name  = "log_disconnections"
    value = "1"
  }

  # Log a statement that waits more than a second for a lock. Lock contention is
  # otherwise invisible until it becomes a timeout.
  parameter {
    name  = "log_lock_waits"
    value = "1"
  }

  # Terminate a transaction idle for longer than this. An idle-in-transaction
  # session holds locks and blocks vacuum indefinitely; one leaked connection
  # can stall the whole database.
  parameter {
    name  = "idle_in_transaction_session_timeout"
    value = "60000"
  }

  tags = local.common_tags

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# Enhanced monitoring role.
#
# Enhanced Monitoring reads metrics from the instance's operating system, which
# ordinary CloudWatch metrics cannot see. It is the difference between "the
# database is slow" and "the database is waiting on disk".
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "monitoring_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["monitoring.rds.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "monitoring" {
  name               = "${local.identifier}-rds-monitoring"
  assume_role_policy = data.aws_iam_policy_document.monitoring_assume_role.json
  tags               = local.common_tags
}

resource "aws_iam_role_policy_attachment" "monitoring" {
  role       = aws_iam_role.monitoring.name
  policy_arn = "arn:${var.aws_partition}:iam::aws:policy/service-role/AmazonRDSEnhancedMonitoringRole"
}

# -----------------------------------------------------------------------------
# The primary instance.
# -----------------------------------------------------------------------------
resource "aws_db_instance" "primary" {
  identifier = local.identifier

  engine         = "postgres"
  engine_version = var.engine_version
  instance_class = var.instance_class

  # -- Storage -----------------------------------------------------------
  allocated_storage = var.allocated_storage
  # Autoscaling headroom. Running out of storage takes the database offline and
  # is entirely avoidable; the ceiling is what stops a runaway from being
  # unbounded.
  max_allocated_storage = var.max_allocated_storage
  storage_type          = "gp3"
  storage_encrypted     = true
  kms_key_id            = var.kms_key_arn

  db_name  = var.database_name
  username = var.master_username

  # AWS generates the master password, stores it in Secrets Manager and rotates
  # it. No password is passed through Terraform, so none appears in the
  # configuration, in the plan output, or in state.
  manage_master_user_password   = true
  master_user_secret_kms_key_id = var.kms_key_arn

  # -- Networking --------------------------------------------------------
  db_subnet_group_name   = aws_db_subnet_group.this.name
  vpc_security_group_ids = [aws_security_group.this.id]
  # Never true. A publicly accessible database is one credential away from a
  # breach, and there is no version of this architecture that needs it.
  publicly_accessible = false
  port                = 5432

  # -- High availability -------------------------------------------------
  # Synchronous standby in another Availability Zone. Zero data loss on
  # failover, and no read capacity: the standby is not queryable.
  multi_az = var.multi_az

  # -- Authentication ----------------------------------------------------
  # Passwordless connections for the application. The pod's IAM role becomes the
  # database credential, so there is no long-lived password to rotate, leak or
  # accidentally log. The bootstrap still needs one; see
  # docs/operations/database-bootstrap.md.
  iam_database_authentication_enabled = true

  # -- Backups -----------------------------------------------------------
  backup_retention_period = var.backup_retention_days
  backup_window           = var.backup_window
  maintenance_window      = var.maintenance_window
  copy_tags_to_snapshot   = true

  # A final snapshot on deletion. Skipping it makes `terraform destroy`
  # irreversible, which is exactly when someone wants it not to be.
  skip_final_snapshot       = var.skip_final_snapshot
  final_snapshot_identifier = var.skip_final_snapshot ? null : "${local.identifier}-final-${var.final_snapshot_suffix}"

  # Refuses deletion until someone deliberately turns this off. The last line of
  # defence against a destroy that was meant for another workspace.
  deletion_protection = var.deletion_protection

  # -- Observability -----------------------------------------------------
  performance_insights_enabled          = true
  performance_insights_retention_period = var.performance_insights_retention_days
  performance_insights_kms_key_id       = var.kms_key_arn

  monitoring_interval = var.enhanced_monitoring_interval
  monitoring_role_arn = aws_iam_role.monitoring.arn

  # PostgreSQL logs to CloudWatch. Upgrade logs are separate and are what
  # explains a failed minor-version upgrade.
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  # -- Maintenance -------------------------------------------------------
  auto_minor_version_upgrade = true
  # Major versions are never automatic: they can change query plans and break
  # extensions, so they are a planned change with a tested rollback.
  allow_major_version_upgrade = false
  apply_immediately           = var.apply_immediately

  parameter_group_name = aws_db_parameter_group.this.name
  ca_cert_identifier   = var.ca_cert_identifier

  tags = merge(local.common_tags, { Name = local.identifier })

  lifecycle {
    ignore_changes = [
      # AWS rotates this; Terraform must not fight it.
      master_user_secret_kms_key_id,
      # The suffix changes on every plan when it is time-derived, which would
      # otherwise show a permanent diff.
      final_snapshot_identifier,
    ]
  }
}

# -----------------------------------------------------------------------------
# Cross-region read replica for disaster recovery.
#
# DISABLED BY DEFAULT, and deliberately so: it roughly doubles the database
# bill, and cross-region data transfer is charged on top.
#
# What it is:  an ASYNCHRONOUS copy in another region, promotable to a standalone
#              primary during a regional failure.
# What it is not:
#   * a backup -- it replicates a mistaken DELETE faithfully;
#   * zero RPO -- it lags, and whatever has not replicated when the primary is
#     lost is lost with it;
#   * automatic -- promotion is a deliberate act, and the application must be
#     repointed afterwards.
#
# Promotion is one-way. A promoted replica cannot become a replica again; the
# original relationship has to be rebuilt from scratch. See the runbook at
# docs/operations/runbooks/cross-region-replica-promotion.md.
# -----------------------------------------------------------------------------
resource "aws_db_instance" "dr_replica" {
  count = var.enable_cross_region_dr_replica ? 1 : 0

  provider = aws.dr

  identifier          = "${local.identifier}-dr"
  replicate_source_db = aws_db_instance.primary.arn

  instance_class = var.dr_replica_instance_class

  # Its own KMS key: a replica in another region cannot use the primary
  # region's key, because a KMS key never leaves its region.
  kms_key_id        = var.dr_kms_key_arn
  storage_encrypted = true

  db_subnet_group_name   = var.dr_db_subnet_group_name
  vpc_security_group_ids = var.dr_security_group_ids
  publicly_accessible    = false

  # A replica that is itself Multi-AZ survives a zone failure in the DR region.
  # Worth it only if the DR region is expected to run production after
  # promotion, which is the whole point of having it.
  multi_az = var.dr_replica_multi_az

  # Backups on the replica, so promoting it does not start from zero
  # recoverability.
  backup_retention_period = var.backup_retention_days
  # Without this a snapshot taken during a DR event carries no tags, so it
  # cannot be attributed to a cost centre or found by a tag-based query --
  # exactly when someone is trying to find it in a hurry.
  copy_tags_to_snapshot = true

  performance_insights_enabled    = true
  performance_insights_kms_key_id = var.dr_kms_key_arn
  monitoring_interval             = var.enhanced_monitoring_interval
  monitoring_role_arn             = aws_iam_role.monitoring.arn

  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]

  auto_minor_version_upgrade = true
  deletion_protection        = var.deletion_protection
  skip_final_snapshot        = var.skip_final_snapshot

  tags = merge(local.common_tags, {
    Name = "${local.identifier}-dr"
    Role = "disaster-recovery-replica"
  })
}
