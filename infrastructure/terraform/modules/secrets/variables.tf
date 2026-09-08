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

variable "kms_key_arn" {
  description = "Customer managed key encrypting these secrets. The services are granted kms:Decrypt on it, bound by a ViaService condition."
  type        = string
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

variable "application_service_role_arn" {
  description = <<-EOT
    The application service's role, the only principal permitted to read the
    pepper.

    Null leaves a resource policy that permits no service at all, which fails
    closed -- the intended direction, but at runtime rather than at plan time.
  EOT
  type        = string
  default     = null
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
