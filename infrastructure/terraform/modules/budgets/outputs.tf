output "budget_name" {
  description = "Budget name, or null when disabled."
  value       = try(aws_budgets_budget.monthly[0].name, null)
}

output "enabled" {
  description = "Whether a budget was created."
  value       = var.enabled
}
