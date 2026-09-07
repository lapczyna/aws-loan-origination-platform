variable "environment" {
  description = "Environment name, prefixed to every resource."
  type        = string
}

variable "stage_name" {
  description = "Stage name. Also an alarm dimension."
  type        = string
  default     = "v1"
}

variable "cognito_user_pool_arns" {
  description = <<-EOT
    User pool ARNs the JWT authorizer trusts.

    PLACEHOLDER by default. A real ARN names the account and the pool, so it is
    supplied at deployment time and never committed.
  EOT
  type        = list(string)
  default     = ["arn:aws:cognito-idp:eu-west-1:000000000000:userpool/eu-west-1_PLACEHOLDER"]
}

variable "internal_nlb_arn" {
  description = "ARN of the INTERNAL network load balancer the VPC Link attaches to."
  type        = string
}

variable "internal_nlb_url" {
  description = "Base URL of the internal load balancer, for the proxy integration."
  type        = string
}

variable "integration_timeout_ms" {
  description = <<-EOT
    Integration timeout in milliseconds.

    Shorter than the client's own timeout, so the gateway gives up first and
    returns a clean 504 rather than leaving the client to guess.
  EOT
  type        = number
  default     = 29000

  validation {
    condition     = var.integration_timeout_ms > 0 && var.integration_timeout_ms <= 29000
    error_message = "API Gateway caps the integration timeout at 29000 ms."
  }
}

variable "access_log_group_arn" {
  description = "CloudWatch log group for access logs."
  type        = string
}

variable "logging_level" {
  description = <<-EOT
    Execution logging level.

    INFO rather than ERROR: without request-level logging an intermittent 403
    cannot be traced to the authorizer. Note this is separate from data tracing,
    which is never enabled because it logs full request and response bodies.
  EOT
  type        = string
  default     = "INFO"

  validation {
    condition     = contains(["OFF", "ERROR", "INFO"], var.logging_level)
    error_message = "Logging level must be OFF, ERROR or INFO."
  }
}

variable "xray_tracing_enabled" {
  description = "Enable X-Ray tracing at the stage. COST: billed per trace."
  type        = bool
  default     = true
}

variable "throttle_rate_limit" {
  description = <<-EOT
    Default steady-state request rate for the whole stage.

    A platform without a default throttle is one misbehaving client away from an
    outage for everyone.
  EOT
  type        = number
  default     = 500
}

variable "throttle_burst_limit" {
  description = "Default burst capacity for the whole stage."
  type        = number
  default     = 1000
}

variable "require_api_key" {
  description = <<-EOT
    Require an API key in addition to the JWT.

    The token says who the caller is; the key is what a usage plan throttles on.
    Without it, throttling is account-wide and one partner's burst degrades
    every other partner.
  EOT
  type        = bool
  default     = true
}

variable "partner_throttle_rate_limit" {
  description = "Per-partner steady-state rate."
  type        = number
  default     = 50
}

variable "partner_throttle_burst_limit" {
  description = "Per-partner burst capacity."
  type        = number
  default     = 100
}

variable "partner_daily_quota" {
  description = "Per-partner daily request quota."
  type        = number
  default     = 100000
}

variable "waf_enabled" {
  description = <<-EOT
    Associate an AWS WAF Web ACL with the stage.

    COST: billed per Web ACL, per rule and per million requests. It is the only
    control that filters before a token is even validated.
  EOT
  type        = bool
  default     = true
}

variable "waf_rate_limit_per_5_minutes" {
  description = <<-EOT
    Requests from one source address in five minutes before WAF blocks it.

    Ahead of the usage plan: the plan throttles an authenticated partner, this
    stops an unauthenticated flood before a token is validated.
  EOT
  type        = number
  default     = 2000
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
  default     = {}
}

variable "waf_log_group_arn" {
  description = <<-EOT
    CloudWatch log group receiving WAF logs.

    Its NAME must begin with "aws-waf-logs-". WAF rejects any other destination,
    and the error does not explain why. The cloudwatch module's
    waf_log_group_arn output already satisfies this.

    Required when waf_enabled is true.
  EOT
  type        = string
  default     = null
}
