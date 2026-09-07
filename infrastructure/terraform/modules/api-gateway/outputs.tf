output "api_id" {
  description = "REST API id."
  value       = aws_api_gateway_rest_api.this.id
}

output "api_name" {
  description = "API name, used as an alarm dimension."
  value       = aws_api_gateway_rest_api.this.name
}

output "stage_name" {
  description = "Stage name, used as an alarm dimension."
  value       = aws_api_gateway_stage.this.stage_name
}

output "invoke_url" {
  description = <<-EOT
    The stage's invoke URL.

    The execute-api hostname, not a custom domain: a real custom domain would
    name the organisation and require a certificate ARN, neither of which is
    committed here.
  EOT
  value       = aws_api_gateway_stage.this.invoke_url
}

output "vpc_link_id" {
  description = "VPC Link id."
  value       = aws_api_gateway_vpc_link.this.id
}

output "web_acl_arn" {
  description = "WAF Web ACL ARN, or null when WAF is disabled."
  value       = try(aws_wafv2_web_acl.this[0].arn, null)
}

output "usage_plan_id" {
  description = "Partner usage plan id, for associating an API key."
  value       = try(aws_api_gateway_usage_plan.partner[0].id, null)
}
