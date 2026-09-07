output "alarm_topic_arn" {
  description = <<-EOT
    ARN of the alarm topic.

    Subscriptions are added at deployment time, never here: an email address or
    a phone number in a repository is personal data, and a Terraform-created
    subscription emails a real person the moment it is applied.
  EOT
  value       = aws_sns_topic.alarms.arn
}

output "api_gateway_access_log_group_arn" {
  description = "Access log group ARN, referenced by the API Gateway stage."
  value       = aws_cloudwatch_log_group.api_gateway_access.arn
}

output "waf_log_group_arn" {
  description = "WAF log group ARN, referenced by the API Gateway module's logging configuration."
  value       = aws_cloudwatch_log_group.waf.arn
}

output "msk_broker_log_group_name" {
  description = "Broker log group name, referenced by the MSK module."
  value       = aws_cloudwatch_log_group.msk_broker.name
}

output "service_log_group_names" {
  description = "Per-service log group names."
  value       = { for name, group in aws_cloudwatch_log_group.services : name => group.name }
}
