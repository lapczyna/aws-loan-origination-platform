# =============================================================================
# AWS Budgets.
#
# DISABLED BY DEFAULT, and requires BOTH an explicit amount and an explicit
# notification address before it will create anything.
#
# The reason for the second requirement: a budget with no subscriber is a budget
# nobody hears about, which is worse than no budget because it creates the
# impression of a cost control. The validation below refuses that configuration
# rather than silently creating a mute alarm.
#
# No email address is committed here. One would be personal data in a repository
# that may become public, and applying it would send mail to a real person.
#
# NOTHING HERE HAS BEEN APPLIED.
# =============================================================================

locals {
  common_tags = merge(var.tags, { Module = "budgets" })

  # AWS Budgets expects "user:<TagKey>$<TagValue>", with a LITERAL dollar sign
  # as the separator.
  #
  # Built with format() rather than string interpolation on purpose. Inside an
  # interpolated string the dollar has to be escaped, and the escaped form is
  # easy to get wrong in a way that fails SILENTLY: the wrong escaping produces
  # the literal text "${var.project_tag}", which matches no resource, so the
  # budget tracks zero spend and never fires. A budget that never fires is worse
  # than no budget, because it looks like a cost control.
  project_cost_filter = format("user:Project%s%s", "$", var.project_tag)
}

resource "aws_budgets_budget" "monthly" {
  count = var.enabled ? 1 : 0

  name         = "${var.environment}-los-monthly"
  budget_type  = "COST"
  limit_amount = tostring(var.monthly_limit_amount)
  limit_unit   = var.currency
  time_unit    = "MONTHLY"

  # Scoped by tag, so the budget tracks THIS platform rather than the whole
  # account. An account-wide budget in a shared account tells you nothing about
  # which workload moved.
  cost_filter {
    name   = "TagKeyValue"
    values = [local.project_cost_filter]
  }

  # Notify on ACTUAL spend crossing the first threshold: something has already
  # happened.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = var.actual_threshold_percent
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = var.notification_email_addresses
  }

  # And on FORECAST, which is the one that gives time to react: it fires when
  # the month is projected to exceed the budget, days before it does.
  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = var.forecast_threshold_percent
    threshold_type             = "PERCENTAGE"
    notification_type          = "FORECASTED"
    subscriber_email_addresses = var.notification_email_addresses
  }

  tags = local.common_tags

  lifecycle {
    precondition {
      condition     = !var.enabled || length(var.notification_email_addresses) > 0
      error_message = "A budget with no subscriber is worse than no budget: it looks like a cost control and notifies nobody. Supply at least one notification address."
    }

    precondition {
      condition     = !var.enabled || var.monthly_limit_amount > 0
      error_message = "Set an explicit monthly limit. A budget of zero alarms immediately and is then ignored."
    }
  }
}
