# =============================================================================
# Backups, and the vault that protects them.
#
# WHAT THIS MODULE IS FOR, AND WHY IT IS NOT THE RDS REPLICA
# ----------------------------------------------------------
# The rds-postgresql module already offers a cross-region read replica. It is
# not a backup, and neither is the Multi-AZ standby:
#
#   Multi-AZ standby      synchronous   protects against a ZONE failure
#   Cross-region replica  asynchronous  protects against a REGION failure
#   Backups (here)                      protect against a MISTAKE
#
# Both forms of replication copy a mistaken DELETE as faithfully as a legitimate
# one, and they do it in seconds. Only a backup lets you go back to before it.
#
# This is therefore the module that protects against the most likely disaster by
# some distance: not a region failing, but somebody running the wrong statement
# against the right database.
#
# THE VAULT LOCK IS THE POINT
# ---------------------------
# A backup an attacker can delete is not a backup. Ransomware operators delete
# backups first, and so does a compromised administrator. A locked vault refuses
# deletion for the retention period -- and in compliance mode refuses it to
# everyone, including the account root user and AWS Support.
#
# COST WARNING: backup storage is billed per gigabyte-month, cold storage is
# cheaper but has a 90-day minimum charge, and CROSS-REGION COPY is billed for
# the transfer AND stores a second copy. See docs/operations/cost.md.
#
# NOTHING HERE HAS BEEN APPLIED.
# =============================================================================

locals {
  name        = "${var.environment}-los"
  common_tags = merge(var.tags, { Module = "disaster-recovery" })
}

# -----------------------------------------------------------------------------
# The primary vault.
# -----------------------------------------------------------------------------
resource "aws_backup_vault" "primary" {
  name        = local.name
  kms_key_arn = var.kms_key_arn

  # Refuses to be destroyed while it holds recovery points. Without this,
  # `terraform destroy` takes the backups with the thing they were protecting.
  force_destroy = false

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# Vault lock.
#
# GOVERNANCE by default, matching the audit bucket's Object Lock and for the same
# reason: compliance mode cannot be undone by anyone, for the full retention
# period, and that is a commitment to make deliberately rather than inherit from
# a module default.
#
# The changeable_for_days window is a grace period during which the lock itself
# can still be removed. After it elapses a COMPLIANCE lock is permanent. Setting
# it to null makes the lock immediate and irreversible, which is why it is not
# the default.
# -----------------------------------------------------------------------------
resource "aws_backup_vault_lock_configuration" "primary" {
  count = var.enable_vault_lock ? 1 : 0

  backup_vault_name = aws_backup_vault.primary.name

  # Null in governance mode, which keeps the lock removable by a principal
  # holding backup:DeleteBackupVaultLockConfiguration.
  changeable_for_days = var.vault_lock_mode == "COMPLIANCE" ? var.vault_lock_changeable_for_days : null

  min_retention_days = var.minimum_retention_days
  max_retention_days = var.maximum_retention_days
}

# -----------------------------------------------------------------------------
# The disaster-recovery vault, in another region.
#
# OFF BY DEFAULT. A second copy in a second region is the control that survives
# losing the first region entirely, and it is billed for the transfer and for
# storing the copy.
# -----------------------------------------------------------------------------
resource "aws_backup_vault" "dr" {
  count    = var.enable_cross_region_copy ? 1 : 0
  provider = aws.dr

  name = "${local.name}-dr"
  # A key in the DR region: a KMS key never leaves the region it was created in,
  # so the primary region's key cannot encrypt anything here.
  kms_key_arn   = var.dr_kms_key_arn
  force_destroy = false

  tags = merge(local.common_tags, { Role = "disaster-recovery" })
}

# -----------------------------------------------------------------------------
# The role AWS Backup assumes.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "backup_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["backup.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "backup" {
  name               = "${local.name}-backup"
  assume_role_policy = data.aws_iam_policy_document.backup_assume_role.json
  tags               = local.common_tags
}

resource "aws_iam_role_policy_attachment" "backup" {
  role       = aws_iam_role.backup.name
  policy_arn = "arn:${var.aws_partition}:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForBackup"
}

# Restore is a SEPARATE policy, and separate on purpose: backing up and restoring
# are different privileges, and a role that can restore can overwrite live data
# with an older copy.
resource "aws_iam_role_policy_attachment" "restore" {
  count = var.grant_restore_permissions ? 1 : 0

  role       = aws_iam_role.backup.name
  policy_arn = "arn:${var.aws_partition}:iam::aws:policy/service-role/AWSBackupServiceRolePolicyForRestores"
}

# -----------------------------------------------------------------------------
# The plan.
#
# Two rules with different retentions, because they answer different questions.
# Daily backups answer "undo what happened this week"; the weekly one answers
# "what did this look like a month ago", which is how long a subtle corruption
# can go unnoticed.
# -----------------------------------------------------------------------------
resource "aws_backup_plan" "this" {
  name = local.name

  rule {
    rule_name         = "daily"
    target_vault_name = aws_backup_vault.primary.name
    schedule          = var.daily_schedule
    # How long the job may wait for a window before being abandoned. Without it,
    # a job delayed behind a long-running one can be silently skipped.
    start_window      = 60
    completion_window = 300

    lifecycle {
      delete_after = var.daily_retention_days
    }

    dynamic "copy_action" {
      for_each = var.enable_cross_region_copy ? [1] : []

      content {
        destination_vault_arn = aws_backup_vault.dr[0].arn

        lifecycle {
          delete_after = var.daily_retention_days
        }
      }
    }

    recovery_point_tags = local.common_tags
  }

  rule {
    rule_name         = "weekly"
    target_vault_name = aws_backup_vault.primary.name
    schedule          = var.weekly_schedule
    start_window      = 60
    completion_window = 600

    lifecycle {
      # Cold storage is cheaper and has a NINETY-DAY MINIMUM CHARGE: moving a
      # recovery point to cold and deleting it a month later costs more than
      # leaving it warm. The transition is therefore only worth it when the
      # retention is long, which is why the daily rule has none.
      cold_storage_after = var.weekly_cold_storage_after_days
      delete_after       = var.weekly_retention_days
    }

    dynamic "copy_action" {
      for_each = var.enable_cross_region_copy ? [1] : []

      content {
        destination_vault_arn = aws_backup_vault.dr[0].arn

        lifecycle {
          cold_storage_after = var.weekly_cold_storage_after_days
          delete_after       = var.weekly_retention_days
        }
      }
    }

    recovery_point_tags = local.common_tags
  }

  # An alert when a backup job fails.
  #
  # A backup that silently stops is worse than no backup: it produces confidence
  # without protection, and it is discovered during a restore. BACKUP_JOB_FAILED
  # and COPY_JOB_FAILED are the two that matter.
  dynamic "advanced_backup_setting" {
    for_each = var.enable_windows_vss ? [1] : []

    content {
      backup_options = { WindowsVSS = "enabled" }
      resource_type  = "EC2"
    }
  }

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# What gets backed up.
#
# Selected BY TAG rather than by ARN. A new resource carrying the project tag is
# protected the day it is created; an ARN list protects exactly what somebody
# remembered to add, and the gap is invisible until a restore.
# -----------------------------------------------------------------------------
resource "aws_backup_selection" "tagged" {
  name         = "${local.name}-tagged"
  iam_role_arn = aws_iam_role.backup.arn
  plan_id      = aws_backup_plan.this.id

  selection_tag {
    type  = "STRINGEQUALS"
    key   = "Project"
    value = var.project_tag
  }

  condition {
    string_equals {
      key   = "aws:ResourceTag/Environment"
      value = var.environment
    }
  }
}

# -----------------------------------------------------------------------------
# Failure notifications.
#
# Wired to the platform's existing alarm topic rather than a new one, so a backup
# failure arrives wherever the other alarms already go.
# -----------------------------------------------------------------------------
resource "aws_backup_vault_notifications" "primary" {
  count = var.notification_topic_arn == null ? 0 : 1

  backup_vault_name   = aws_backup_vault.primary.name
  sns_topic_arn       = var.notification_topic_arn
  backup_vault_events = var.notification_events
}
