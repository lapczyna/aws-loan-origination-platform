variable "environment" {
  description = "Environment name. Secret names are \"<environment>/los/...\", matching what the Helm chart's SecretProviderClass mounts."
  type        = string
}

variable "aws_partition" {
  description = "AWS partition, for constructing ARNs."
  type        = string
  default     = "aws"
}

variable "account_id" {
  description = "Account id, for constructing ARNs in the resource policies."
  type        = string
}

variable "services" {
  description = <<-EOT
    Services that each get their own database credential.

    One secret per service rather than one shared credential: a shared password
    means a compromise of any service is a compromise of every service's database
    access, and rotating it requires restarting all of them at once.
  EOT
  type        = list(string)
  default     = ["application-service", "workflow-service", "document-service", "audit-service"]
}

variable "kms_key_arn" {
  description = "Customer managed key encrypting these secrets. The services are granted kms:Decrypt on it, bound by a ViaService condition."
  type        = string
}

variable "recovery_window_in_days" {
  description = <<-EOT
    Deletion window for the service credentials.

    A window, not immediate deletion. Zero makes `terraform destroy`
    irreversible for the resources whose loss is hardest to recover from.
  EOT
  type        = number
  default     = 30

  validation {
    condition     = var.recovery_window_in_days >= 7
    error_message = "A recovery window shorter than seven days leaves no realistic chance to notice an accidental deletion."
  }
}

variable "pepper_recovery_window_in_days" {
  description = <<-EOT
    Deletion window for the applicant pepper.

    Longer than the others, deliberately. Losing the pepper makes every stored
    applicant reference uninterpretable, and no database backup recovers it: the
    references are HMACs, and the pepper is the key.
  EOT
  type        = number
  default     = 30

  validation {
    condition     = var.pepper_recovery_window_in_days >= 30
    error_message = "The pepper is irreplaceable. Its recovery window must be at least 30 days."
  }
}

# -----------------------------------------------------------------------------
# Rotation
# -----------------------------------------------------------------------------
variable "rotation_lambda_arn" {
  description = <<-EOT
    Rotation function for the database credentials, or null.

    NULL, and the module says so rather than implying a rotation that never
    happens. Secrets Manager rotation needs a Lambda that knows how to change the
    credential at its source; writing one is real work, not a flag.

    The RDS MASTER password is separate and already handled: the rds-postgresql
    module sets manage_master_user_password, so AWS generates, stores and rotates
    it and it never passes through Terraform.

    The applicant pepper is deliberately never rotated on a schedule. See
    ADR-0010.
  EOT
  type        = string
  default     = null
}

variable "rotation_days" {
  description = "Rotation interval, when a rotation function exists."
  type        = number
  default     = 30
}

# -----------------------------------------------------------------------------
# Resource policies
# -----------------------------------------------------------------------------
variable "enforce_resource_policies" {
  description = <<-EOT
    Attach a resource policy to each secret naming who may read it.

    Defence in depth: the iam module already grants each service its own secret
    and no other, and this says the same from the resource's side, so a
    mistakenly broad identity policy elsewhere does not open the secret. Both
    controls must allow.
  EOT
  type        = bool
  default     = true
}

variable "service_role_arns" {
  description = <<-EOT
    Service name to IAM role ARN, from the iam module.

    A service missing from this map gets a resource policy that permits no
    service at all, which fails closed. That is the intended direction, but it
    fails at runtime rather than at plan time, so keep it in step with the iam
    module's outputs.
  EOT
  type        = map(string)
  default     = {}
}

variable "secret_administrator_role_arns" {
  description = <<-EOT
    Roles permitted to read every secret, for placing and rotating values.

    Named explicitly. The values are placed out of band by a human or a rotation
    function, and this is who that human is.
  EOT
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
