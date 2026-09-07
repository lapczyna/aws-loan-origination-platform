# =============================================================================
# Inputs.
#
# No default names an account, a region or an environment. A module that
# defaults to something account-specific is a module that quietly creates the
# wrong thing when a caller forgets a variable.
# =============================================================================

variable "environment" {
  description = "Environment name, used as a prefix for every resource this module creates."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,20}$", var.environment))
    error_message = "environment must be lower-case alphanumeric with hyphens, 2-21 characters."
  }
}

variable "name" {
  description = "Short name for this database, appended to the environment prefix."
  type        = string
  default     = "los"
}

variable "vpc_id" {
  description = "VPC the database lives in."
  type        = string
}

variable "private_subnet_ids" {
  description = <<-EOT
    Private subnets for the DB subnet group. Must span at least two Availability
    Zones: Multi-AZ has nowhere to place the standby otherwise.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "At least two subnets in different Availability Zones are required for Multi-AZ."
  }
}

variable "allowed_security_group_ids" {
  description = <<-EOT
    Security groups permitted to connect on 5432.

    Security groups, not CIDRs. A CIDR rule grants access to whatever holds an
    address in that range now or later; a security-group rule follows the
    workload it was written for.
  EOT
  type        = list(string)
}

variable "kms_key_arn" {
  description = <<-EOT
    Customer managed KMS key for storage, Performance Insights and the
    AWS-managed master password secret.

    Customer managed rather than the AWS-managed aws/rds key, because only a
    customer managed key can have a key policy, be audited through CloudTrail
    per grant, and be disabled to render the data unreadable during an incident.
  EOT
  type        = string
}

variable "engine_version" {
  description = "PostgreSQL engine version. Matches the container used in local development and CI."
  type        = string
  default     = "17.6"
}

variable "parameter_group_family" {
  description = "Parameter group family. Must correspond to the engine version's major line."
  type        = string
  default     = "postgres17"
}

variable "instance_class" {
  description = <<-EOT
    Primary instance class.

    COST: the single largest lever on the database bill, and Multi-AZ doubles
    whatever it is. See docs/operations/cost.md.
  EOT
  type        = string
  default     = "db.t4g.medium"
}

variable "allocated_storage" {
  description = "Initial storage in gibibytes."
  type        = number
  default     = 50

  validation {
    condition     = var.allocated_storage >= 20
    error_message = "RDS requires at least 20 GiB."
  }
}

variable "max_allocated_storage" {
  description = <<-EOT
    Storage autoscaling ceiling in gibibytes.

    Running out of storage takes the database offline. The ceiling is what stops
    a runaway query or a log flood from growing the bill without limit.
  EOT
  type        = number
  default     = 200
}

variable "database_name" {
  description = "Initial database name."
  type        = string
  default     = "los"
}

variable "master_username" {
  description = <<-EOT
    Master username.

    Deliberately NOT "postgres" or "admin": a predictable name is half of a
    credential-stuffing attempt. The PASSWORD is never set here -- AWS generates
    and rotates it in Secrets Manager.
  EOT
  type        = string
  default     = "los_master"
}

variable "multi_az" {
  description = <<-EOT
    Synchronous standby in a second Availability Zone.

    COST: doubles the instance charge. It is the difference between a zone
    failure being a two-minute blip and being an outage until someone restores
    a backup.
  EOT
  type        = bool
  default     = true
}

variable "backup_retention_days" {
  description = <<-EOT
    Automated backup retention, in days. Also the point-in-time recovery window.

    Zero DISABLES backups and PITR entirely. It is never an acceptable value for
    a system of record, which is why the validation refuses it.
  EOT
  type        = number
  default     = 14

  validation {
    condition     = var.backup_retention_days >= 7 && var.backup_retention_days <= 35
    error_message = "Backup retention must be between 7 and 35 days. Zero disables point-in-time recovery."
  }
}

variable "backup_window" {
  description = "Daily backup window, UTC. Should not overlap the maintenance window."
  type        = string
  default     = "02:00-03:00"
}

variable "maintenance_window" {
  description = "Weekly maintenance window, UTC."
  type        = string
  default     = "sun:03:30-sun:04:30"
}

variable "deletion_protection" {
  description = <<-EOT
    Refuse to delete the instance until this is deliberately turned off.

    The last line of defence against a destroy aimed at the wrong workspace.
  EOT
  type        = bool
  default     = true
}

variable "skip_final_snapshot" {
  description = <<-EOT
    Skip the final snapshot on deletion.

    True makes deletion irreversible, which is precisely when someone wishes it
    were not. Only ever appropriate for a throwaway environment.
  EOT
  type        = bool
  default     = false
}

variable "final_snapshot_suffix" {
  description = "Suffix making the final snapshot identifier unique. Supplied by the caller so it is not time-derived in state."
  type        = string
  default     = "snapshot"
}

variable "apply_immediately" {
  description = <<-EOT
    Apply modifications at once rather than in the maintenance window.

    True can cause an immediate reboot. Appropriate in development, rarely in
    production.
  EOT
  type        = bool
  default     = false
}

variable "performance_insights_retention_days" {
  description = <<-EOT
    Performance Insights retention.

    7 days is free. Anything longer is billed, so the default stays at the free
    tier and longer retention is an explicit decision.
  EOT
  type        = number
  default     = 7

  validation {
    condition     = contains([7, 31, 62, 93, 124, 155, 186, 217, 248, 279, 310, 341, 372, 403, 434, 465, 496, 527, 558, 589, 620, 651, 682, 713, 731], var.performance_insights_retention_days)
    error_message = "Performance Insights retention must be 7 (free), 731, or a multiple of 31 up to 713."
  }
}

variable "enhanced_monitoring_interval" {
  description = <<-EOT
    Enhanced Monitoring granularity in seconds. 0 disables it.

    COST: billed per instance as CloudWatch Logs ingestion, and one-second
    granularity is materially more expensive than sixty.
  EOT
  type        = number
  default     = 60

  validation {
    condition     = contains([0, 1, 5, 10, 15, 30, 60], var.enhanced_monitoring_interval)
    error_message = "Enhanced Monitoring interval must be one of 0, 1, 5, 10, 15, 30, 60."
  }
}

variable "slow_query_threshold_ms" {
  description = <<-EOT
    Log statements slower than this, in milliseconds.

    Deliberately not zero. Zero logs EVERY statement including bound parameters,
    which for this platform means applicant names and email addresses in
    CloudWatch Logs.
  EOT
  type        = number
  default     = 1000

  validation {
    condition     = var.slow_query_threshold_ms > 0
    error_message = "Must be greater than zero. Logging every statement writes bound parameters, and those contain personal data."
  }
}

variable "ca_cert_identifier" {
  description = "RDS certificate authority bundle. rds-ca-rsa2048-g1 expires in 2061."
  type        = string
  default     = "rds-ca-rsa2048-g1"
}

variable "aws_partition" {
  description = "AWS partition. 'aws' commercially; differs in GovCloud and China."
  type        = string
  default     = "aws"
}

# --- Disaster recovery -------------------------------------------------------

variable "enable_cross_region_dr_replica" {
  description = <<-EOT
    Create an asynchronous cross-region read replica.

    DISABLED BY DEFAULT because it roughly doubles the database bill and adds
    cross-region data transfer charges.

    It is NOT a backup and NOT zero RPO: it lags, and whatever has not
    replicated when the primary is lost is lost. Promotion is manual, one-way,
    and requires the application to be repointed. See
    docs/operations/runbooks/cross-region-replica-promotion.md.
  EOT
  type        = bool
  default     = false
}

variable "dr_replica_instance_class" {
  description = "Instance class for the DR replica. May be smaller than the primary if it is only expected to serve reads until promoted."
  type        = string
  default     = "db.t4g.medium"
}

variable "dr_replica_multi_az" {
  description = "Make the DR replica itself Multi-AZ. Worth the cost only if the DR region is expected to run production after promotion."
  type        = bool
  default     = false
}

variable "dr_kms_key_arn" {
  description = <<-EOT
    KMS key in the DR REGION.

    A separate key is not optional: a KMS key never leaves its region, so the
    primary's key cannot encrypt a replica elsewhere.
  EOT
  type        = string
  default     = null
}

variable "dr_db_subnet_group_name" {
  description = "DB subnet group in the DR region."
  type        = string
  default     = null
}

variable "dr_security_group_ids" {
  description = "Security groups in the DR region."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Tags applied to every resource. Cost allocation depends on these being consistent."
  type        = map(string)
  default     = {}
}
