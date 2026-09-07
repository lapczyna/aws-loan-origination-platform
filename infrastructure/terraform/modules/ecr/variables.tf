variable "environment" {
  description = "Environment name, used as the repository namespace."
  type        = string
}

variable "account_id" {
  description = "Account id, used to scope the repository policy."
  type        = string
}

variable "repository_names" {
  description = "Repositories to create, one per service."
  type        = list(string)
  default     = ["application-service", "document-service", "workflow-service", "audit-service"]
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for image encryption at rest."
  type        = string
}

variable "untagged_retention_days" {
  description = "Days an untagged image is kept. They are build artefacts, so this is short."
  type        = number
  default     = 3
}

variable "tagged_image_count" {
  description = <<-EOT
    Tagged images to retain per repository.

    A count rather than an age: "the last 20 releases" is a set of rollback
    targets, whereas "anything from the last 90 days" could be zero images for a
    service that has not changed.
  EOT
  type        = number
  default     = 20
}

variable "aws_partition" {
  description = "AWS partition. 'aws' commercially."
  type        = string
  default     = "aws"
}

variable "tags" {
  description = "Tags applied to every repository."
  type        = map(string)
  default     = {}
}
