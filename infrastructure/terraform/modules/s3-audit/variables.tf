variable "environment" {
  description = "Environment name, prefixed to the bucket name."
  type        = string
}

variable "name" {
  description = "Bucket suffix."
  type        = string
  default     = "los-audit"
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for object encryption."
  type        = string
}

variable "object_lock_enabled" {
  description = <<-EOT
    Enable S3 Object Lock.

    CANNOT BE ENABLED LATER. Object Lock is set when the bucket is created or
    never, so this is one of the few decisions here that cannot be corrected.
  EOT
  type        = bool
  default     = true
}

variable "object_lock_mode" {
  description = <<-EOT
    GOVERNANCE or COMPLIANCE.

    GOVERNANCE stops accidents: a principal holding
    s3:BypassGovernanceRetention can still correct a genuine mistake.

    COMPLIANCE stops EVERYONE, including the account root user and AWS support,
    for the full retention period, with no exception and no way back. Correct
    when a regulator requires it; a serious commitment otherwise.
  EOT
  type        = string
  default     = "GOVERNANCE"

  validation {
    condition     = contains(["GOVERNANCE", "COMPLIANCE"], var.object_lock_mode)
    error_message = "Object Lock mode must be GOVERNANCE or COMPLIANCE."
  }
}

variable "retention_days" {
  description = <<-EOT
    Default retention for a newly written audit object. Seven years by default,
    a common financial-services obligation.

    COST: under COMPLIANCE mode nothing can be deleted for this long, so storage
    only ever grows. That is the intended behaviour and should be a costed
    decision.
  EOT
  type        = number
  default     = 2555
}

variable "audit_administrator_role_arns" {
  description = <<-EOT
    Roles exempt from the deletion deny.

    Empty by default, meaning nobody may delete. A named exemption keeps a
    legitimate retention change possible through a reviewed path rather than
    making it impossible.
  EOT
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Tags applied to the bucket."
  type        = map(string)
  default     = {}
}

variable "access_log_bucket_name" {
  description = <<-EOT
    Bucket receiving S3 server access logs for the audit bucket. Null disables
    logging.

    Must be a DIFFERENT bucket. Logging a bucket into itself is an infinite
    loop, because every log write is an access that must be logged.

    Reading the audit trail is itself an event worth recording: "who looked at
    the audit trail" is a question that gets asked during an investigation, and
    the audit trail cannot answer it about itself.
  EOT
  type        = string
  default     = null
}

variable "archive_transition_days" {
  description = <<-EOT
    Age at which audit objects move to GLACIER_IR.

    Retention here is measured in years, and the overwhelming majority of those
    objects are never read again. Instant Retrieval keeps millisecond access --
    an investigation should not wait hours for a restore -- at a fraction of
    Standard's storage price.

    Transitions do not conflict with Object Lock: the lock forbids deleting and
    overwriting an object version, not moving it between storage classes.
  EOT
  type        = number
  default     = 90

  validation {
    # S3 refuses a GLACIER_IR transition earlier than this.
    condition     = var.archive_transition_days >= 90
    error_message = "S3 will not transition an object to GLACIER_IR before 90 days."
  }
}
