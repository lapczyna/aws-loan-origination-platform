variable "enabled" {
  description = <<-EOT
    Create the budget.

    False by default. Enabling it requires an explicit amount AND at least one
    notification address; the module refuses to create a budget nobody hears
    about.
  EOT
  type        = bool
  default     = false
}

variable "environment" {
  description = "Environment name, prefixed to the budget name."
  type        = string
}

variable "project_tag" {
  description = "Value of the Project tag the budget filters on, so it tracks this platform rather than the whole account."
  type        = string
  default     = "loan-origination-platform"
}

variable "monthly_limit_amount" {
  description = <<-EOT
    Monthly budget.

    No default that would work: a budget amount is a business decision, and a
    plausible-looking default is one nobody thinks about.
  EOT
  type        = number
  default     = 0
}

variable "currency" {
  description = "Budget currency."
  type        = string
  default     = "USD"
}

variable "notification_email_addresses" {
  description = <<-EOT
    Addresses notified when a threshold is crossed.

    EMPTY BY DEFAULT AND DELIBERATELY SO. An address committed here would be
    personal data in a repository that may become public, and applying it would
    send mail to a real person. Supplied at deployment time.
  EOT
  type        = list(string)
  default     = []
}

variable "actual_threshold_percent" {
  description = "Percentage of the budget at which ACTUAL spend triggers a notification."
  type        = number
  default     = 80
}

variable "forecast_threshold_percent" {
  description = <<-EOT
    Percentage at which FORECAST spend triggers a notification.

    The more useful of the two: it fires when the month is projected to exceed
    the budget, days before it actually does.
  EOT
  type        = number
  default     = 100
}

variable "tags" {
  description = "Tags applied to the budget."
  type        = map(string)
  default     = {}
}
