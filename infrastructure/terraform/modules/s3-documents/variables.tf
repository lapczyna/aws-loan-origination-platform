variable "environment" {
  description = "Environment name, prefixed to the bucket name."
  type        = string
}

variable "name" {
  description = "Bucket suffix. Combined with the environment to form a globally unique name."
  type        = string
  default     = "los-documents"
}

variable "kms_key_arn" {
  description = <<-EOT
    Customer managed KMS key for object encryption.

    Also enforced by the bucket policy: a write using any other key is denied,
    so the encryption guarantee cannot be bypassed by an uploader.
  EOT
  type        = string
}

variable "quarantine_retention_days" {
  description = <<-EOT
    Days before an unscanned quarantine object expires.

    Long enough that a slow scan is never the reason a document disappears,
    short enough that abandoned uploads do not accumulate indefinitely.
  EOT
  type        = number
  default     = 7
}

variable "accepted_transition_days" {
  description = "Days before an accepted document moves to infrequent-access storage."
  type        = number
  default     = 90
}

variable "noncurrent_version_retention_days" {
  description = "Days a superseded object version is retained."
  type        = number
  default     = 30
}

variable "access_log_bucket_name" {
  description = <<-EOT
    Bucket receiving S3 server access logs. Null disables logging.

    Must be a DIFFERENT bucket: logging a bucket into itself is an infinite
    loop, because every log write is an access that must be logged.
  EOT
  type        = string
  default     = null
}

variable "cors_allowed_origins" {
  description = <<-EOT
    Origins permitted to PUT directly to S3 through a presigned URL.

    Empty by default. A wildcard would let any website script an upload with a
    URL it had somehow obtained.
  EOT
  type        = list(string)
  default     = []

  validation {
    condition     = !contains(var.cors_allowed_origins, "*")
    error_message = "A wildcard CORS origin is not permitted on the documents bucket."
  }
}

variable "force_destroy" {
  description = <<-EOT
    Allow `terraform destroy` to delete a bucket that still contains objects.

    False everywhere it matters: these are customer documents.
  EOT
  type        = bool
  default     = false
}

variable "tags" {
  description = "Tags applied to the bucket."
  type        = map(string)
  default     = {}
}
