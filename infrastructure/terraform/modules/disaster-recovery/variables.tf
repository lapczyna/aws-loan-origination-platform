variable "environment" {
  description = "Environment name, prefixed to the vault and plan names."
  type        = string
}

variable "aws_partition" {
  description = "AWS partition, for building managed-policy ARNs."
  type        = string
  default     = "aws"
}

variable "project_tag" {
  description = <<-EOT
    Value of the Project tag that selects resources for backup.

    Selection is BY TAG rather than by ARN. A new resource carrying the tag is
    protected the day it is created; an ARN list protects exactly what somebody
    remembered to add, and the gap is invisible until a restore.
  EOT
  type        = string
  default     = "loan-origination-platform"
}

variable "kms_key_arn" {
  description = "Customer managed key encrypting the primary vault."
  type        = string
}

# -----------------------------------------------------------------------------
# Vault lock
# -----------------------------------------------------------------------------
variable "enable_vault_lock" {
  description = <<-EOT
    Lock the vault so recovery points cannot be deleted before their retention
    expires.

    A backup an attacker can delete is not a backup. Ransomware operators delete
    backups first, and so does a compromised administrator.
  EOT
  type        = bool
  default     = true
}

variable "vault_lock_mode" {
  description = <<-EOT
    GOVERNANCE or COMPLIANCE.

    GOVERNANCE by default, matching the audit bucket's Object Lock and for the
    same reason: COMPLIANCE cannot be undone by anyone -- including the account
    root user and AWS Support -- for the full retention period, and that is a
    commitment to make deliberately rather than inherit from a module default.

    GOVERNANCE can be removed by a principal holding
    backup:DeleteBackupVaultLockConfiguration, so it protects against accident
    rather than against intent.
  EOT
  type        = string
  default     = "GOVERNANCE"

  validation {
    condition     = contains(["GOVERNANCE", "COMPLIANCE"], var.vault_lock_mode)
    error_message = "vault_lock_mode must be GOVERNANCE or COMPLIANCE."
  }
}

variable "vault_lock_changeable_for_days" {
  description = <<-EOT
    Grace period before a COMPLIANCE lock becomes permanent.

    Only meaningful in COMPLIANCE mode. After it elapses the lock cannot be
    removed by anyone, ever, so a mistake made here is not recoverable. Three
    days is the AWS minimum; longer is safer.
  EOT
  type        = number
  default     = 3

  validation {
    condition     = var.vault_lock_changeable_for_days >= 3
    error_message = "AWS requires at least 3 days, and a shorter grace period leaves no time to notice a mistake that cannot be undone."
  }
}

variable "minimum_retention_days" {
  description = "Shortest retention the locked vault will accept. A plan asking for less is rejected."
  type        = number
  default     = 7
}

variable "maximum_retention_days" {
  description = "Longest retention the locked vault will accept. A ceiling exists so a mistake cannot pin storage cost for years."
  type        = number
  default     = 400
}

# -----------------------------------------------------------------------------
# Schedule and retention
# -----------------------------------------------------------------------------
variable "daily_schedule" {
  description = "Cron for the daily backup, in UTC. Outside the busy period and clear of the database maintenance window."
  type        = string
  default     = "cron(0 3 * * ? *)"
}

variable "daily_retention_days" {
  description = <<-EOT
    Retention for daily recovery points.

    Answers "undo what happened this week". No cold-storage transition: cold
    storage bills a 90-day minimum, so moving a 30-day point to cold costs MORE
    than leaving it warm.
  EOT
  type        = number
  default     = 30
}

variable "weekly_schedule" {
  description = "Cron for the weekly backup, in UTC."
  type        = string
  default     = "cron(0 4 ? * SUN *)"
}

variable "weekly_retention_days" {
  description = <<-EOT
    Retention for weekly recovery points.

    Answers "what did this look like a month ago", which is how long a subtle
    corruption can go unnoticed before anyone asks.
  EOT
  type        = number
  default     = 365
}

variable "weekly_cold_storage_after_days" {
  description = <<-EOT
    Age at which a weekly recovery point moves to cold storage.

    Worth it only because the weekly retention is long. Cold storage bills a
    90-day minimum whatever the actual retention, so the transition must be at
    least 90 days before deletion or it costs more than it saves.
  EOT
  type        = number
  default     = 90
}

# -----------------------------------------------------------------------------
# Cross-region copy
# -----------------------------------------------------------------------------
variable "enable_cross_region_copy" {
  description = <<-EOT
    Copy every recovery point to a vault in another region.

    OFF BY DEFAULT. It is the control that survives losing the primary region
    entirely, and it is billed for the transfer AND for storing a second copy of
    everything.

    Requires the aws.dr provider alias and a KMS key in the DR region: a KMS key
    never leaves the region it was created in.
  EOT
  type        = bool
  default     = false
}

variable "dr_kms_key_arn" {
  description = "Key encrypting the DR-region vault. Required when enable_cross_region_copy is true."
  type        = string
  default     = null
}

# -----------------------------------------------------------------------------
# Permissions and notifications
# -----------------------------------------------------------------------------
variable "grant_restore_permissions" {
  description = <<-EOT
    Attach the restore policy to the backup role as well as the backup policy.

    Separate because backing up and restoring are different privileges: a role
    that can restore can overwrite live data with an older copy. Enable it when a
    restore is actually being performed, or give restores their own role.
  EOT
  type        = bool
  default     = false
}

variable "notification_topic_arn" {
  description = <<-EOT
    SNS topic for backup job events. Null disables notifications.

    A backup that silently stops is worse than no backup: it produces confidence
    without protection, and it is discovered during a restore.
  EOT
  type        = string
  default     = null
}

variable "notification_events" {
  description = "Vault events to publish. The FAILED events are the ones that matter; the rest are noise that trains people to ignore the topic."
  type        = list(string)
  default     = ["BACKUP_JOB_FAILED", "COPY_JOB_FAILED", "RESTORE_JOB_FAILED"]
}

variable "enable_windows_vss" {
  description = "Windows volume shadow copy for EC2 backups. No Windows instances exist here; present so the plan does not need editing if one appears."
  type        = bool
  default     = false
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
