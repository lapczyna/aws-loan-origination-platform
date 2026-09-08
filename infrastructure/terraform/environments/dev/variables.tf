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
  default     = "dev"
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
  description = "VPC CIDR."
  type        = string
  default     = "10.20.0.0/16"
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

variable "internal_tls_certificate_arn" {
  description = <<-EOT
    ACM certificate for the internal load balancer's TLS listener.

    Null by default, which turns TLS off -- and with it the NLB's access logs,
    because an NLB writes them for TLS listeners only. Supply a certificate for
    anything beyond a first apply.
  EOT
  type        = string
  default     = null
}

variable "internal_alb_arn" {
  description = <<-EOT
    The ALB the Helm chart's Ingress creates, for the NLB to forward to.

    NULL ON A FIRST APPLY, deliberately. The ALB is created by the AWS Load
    Balancer Controller once the chart is installed, which needs the cluster,
    which needs the load balancer. Supply it on a second apply.
  EOT
  type        = string
  default     = null
}

variable "cognito_user_pool_arns" {
  description = <<-EOT
    Cognito user pools the API Gateway authorizer trusts.

    PLACEHOLDER, and it names no real account or pool.
  EOT
  type        = list(string)
  default     = ["arn:aws:cognito-idp:eu-west-1:000000000000:userpool/eu-west-1_EXAMPLE"]
}
