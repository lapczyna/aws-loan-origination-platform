# =============================================================================
# Outputs.
#
# Endpoints and identifiers only. NO password, NO secret value, NO connection
# string containing one. An output is written to state and printed by
# `terraform output`, so anything sensitive here is a secret in two more places
# -- including any CI log that runs the command.
#
# Every output tolerates the deployment being disabled, so `terraform output`
# works against an environment that has created nothing.
# =============================================================================

output "vpc_id" {
  description = "VPC id."
  value       = try(module.networking[0].vpc_id, null)
}

output "database_endpoint" {
  description = "Database endpoint. The password lives in Secrets Manager and is never output."
  value       = try(module.database[0].endpoint, null)
}

output "database_master_secret_arn" {
  description = <<-EOT
    ARN of the Secrets Manager secret holding the AWS-managed master password.

    The ARN only. Terraform never reads the value, so it never reaches state.
  EOT
  value       = try(module.database[0].master_user_secret_arn, null)
}

output "kafka_bootstrap_brokers" {
  description = "MSK bootstrap brokers for IAM-authenticated TLS."
  value       = try(module.events[0].bootstrap_brokers_sasl_iam, null)
}

output "documents_bucket_name" {
  description = "Documents bucket."
  value       = try(module.documents_bucket[0].bucket_name, null)
}

output "audit_bucket_name" {
  description = "Audit bucket."
  value       = try(module.audit_bucket[0].bucket_name, null)
}

output "ecr_repository_urls" {
  description = "ECR repository URLs, keyed by service."
  value       = try(module.ecr[0].repository_urls, {})
}

output "alarm_topic_arn" {
  description = "SNS topic every alarm publishes to. Subscriptions are added at deployment time."
  value       = try(module.cloudwatch[0].alarm_topic_arn, null)
}

output "deployment_enabled" {
  description = "Whether this environment created anything. False is the default."
  value       = var.enable_deployment
}
