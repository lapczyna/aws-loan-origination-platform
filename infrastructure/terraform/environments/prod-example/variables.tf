# =============================================================================
# Development environment inputs.
#
# The two safety defaults are the important part of this file.
# =============================================================================

variable "enable_deployment" {
  description = <<-EOT
    Create anything at all.

    FALSE BY DEFAULT. Every module in this environment is gated on it, so a plan
    with the default produces no resources and an accidental apply creates
    nothing.

    This is the difference between a mistaken `terraform apply` costing zero and
    costing several hundred dollars a month.
  EOT
  type        = bool
  default     = false
}

variable "environment" {
  description = "Environment name."
  type        = string
  default     = "prod"
}

variable "region" {
  description = "Primary region."
  type        = string
  default     = "eu-west-1"
}

variable "dr_region" {
  description = <<-EOT
    Region for the disaster-recovery replica.

    Configured even when the replica is disabled: the RDS module declares the
    provider alias as required, so a caller who later enables the replica cannot
    accidentally create it in the primary region.
  EOT
  type        = string
  default     = "eu-central-1"
}

variable "vpc_cidr" {
  description = "VPC CIDR. Distinct from development, so the two can be peered without colliding."
  type        = string
  default     = "10.30.0.0/16"
}

variable "enable_cross_region_dr_replica" {
  description = <<-EOT
    Create the cross-region disaster-recovery replica.

    FALSE BY DEFAULT. It roughly DOUBLES the database bill and adds cross-region
    data transfer charges.

    It is not a backup: it replicates a mistaken DELETE faithfully. It is not
    zero RPO: it lags, and whatever has not replicated when the primary is lost
    is lost with it. Promotion is manual and one-way, and the application must
    be repointed afterwards. See
    docs/operations/runbooks/cross-region-replica-promotion.md.
  EOT
  type        = bool
  default     = false
}

variable "owner_tag" {
  description = <<-EOT
    Owning team, applied as a tag to every resource.

    A team name, never an individual: a personal name in a tag is personal data
    that outlives the person's involvement.
  EOT
  type        = string
  default     = "platform-engineering"
}

variable "cost_centre_tag" {
  description = "Cost centre, applied as a tag for cost allocation."
  type        = string
  default     = "engineering"
}

variable "enable_budget" {
  description = <<-EOT
    Create an AWS Budget.

    False by default. Requires both an amount and a notification address; the
    module refuses to create a budget nobody hears about.
  EOT
  type        = bool
  default     = false
}

variable "budget_monthly_limit" {
  description = "Monthly budget. A business decision, so there is no plausible default."
  type        = number
  default     = 0
}

variable "budget_notification_emails" {
  description = <<-EOT
    Budget notification addresses.

    EMPTY BY DEFAULT. An address committed here would be personal data in a
    repository that may become public, and applying it would send mail to a real
    person.
  EOT
  type        = list(string)
  default     = []
}

variable "cluster_admin_role_arns" {
  description = <<-EOT
    IAM ROLES granted cluster-admin on the EKS cluster.

    PLACEHOLDER, and it names no real account. The cluster creator is
    deliberately NOT granted admin automatically -- that default hands
    cluster-admin to whichever principal happened to run `terraform apply`, often
    a CI role, which then holds standing admin nobody decided to grant.

    Roles, never users: a role can be assumed with MFA and its session is
    time-bounded and auditable.
  EOT
  type        = list(string)
  default     = ["arn:aws:iam::000000000000:role/platform-engineering-admin"]
}
