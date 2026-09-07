variable "alias_name" {
  description = "Alias for the key, without the 'alias/' prefix."
  type        = string
}

variable "description" {
  description = "What this key protects. Shown in the console and in CloudTrail."
  type        = string
}

variable "deletion_window_in_days" {
  description = <<-EOT
    Days between requesting deletion and the key being destroyed.

    Deleting a KMS key makes everything encrypted under it permanently
    unreadable. The window is the only opportunity to notice the mistake, so the
    default is the maximum.
  EOT
  type        = number
  default     = 30

  validation {
    condition     = var.deletion_window_in_days >= 7 && var.deletion_window_in_days <= 30
    error_message = "The deletion window must be between 7 and 30 days."
  }
}

variable "multi_region" {
  description = <<-EOT
    Create a multi-Region key.

    Only where a replica in another region must decrypt the same data. A
    multi-Region key widens the blast radius of a compromised key policy to
    every region it is replicated into.
  EOT
  type        = bool
  default     = false
}

variable "service_principals" {
  description = "AWS service principals permitted to use the key, for example rds.amazonaws.com."
  type        = list(string)
  default     = []
}

variable "via_services" {
  description = <<-EOT
    kms:ViaService values constraining the service grant.

    Without this condition the service statement grants use of the key to any
    principal that can call KMS, not merely to requests genuinely made through
    that service.
  EOT
  type        = list(string)
  default     = []
}

variable "aws_partition" {
  description = "AWS partition. 'aws' commercially."
  type        = string
  default     = "aws"
}

variable "tags" {
  description = "Tags applied to the key."
  type        = map(string)
  default     = {}
}
