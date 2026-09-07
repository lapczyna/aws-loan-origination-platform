# =============================================================================
# Outputs.
#
# Deliberately NO password, no secret value and no connection string containing
# either. The master password lives in Secrets Manager and is referenced by ARN;
# a Terraform output is written to state and printed by `terraform output`, so
# anything sensitive placed here is a secret in two more places.
# =============================================================================

output "endpoint" {
  description = "Primary endpoint, host:port."
  value       = aws_db_instance.primary.endpoint
}

output "address" {
  description = "Primary hostname."
  value       = aws_db_instance.primary.address
}

output "port" {
  description = "Primary port."
  value       = aws_db_instance.primary.port
}

output "database_name" {
  description = "Initial database name."
  value       = aws_db_instance.primary.db_name
}

output "instance_identifier" {
  description = "Primary instance identifier, for alarms and runbooks."
  value       = aws_db_instance.primary.identifier
}

output "instance_arn" {
  description = "Primary instance ARN."
  value       = aws_db_instance.primary.arn
}

output "resource_id" {
  description = <<-EOT
    The instance's resource identifier.

    Needed for IAM database authentication: an rds-db:connect policy is scoped
    to this value, not to the instance name, so the policy survives a rename.
  EOT
  value       = aws_db_instance.primary.resource_id
}

output "master_user_secret_arn" {
  description = <<-EOT
    ARN of the Secrets Manager secret holding the AWS-managed master password.

    The ARN only. The VALUE is never read by Terraform, so it never reaches
    state, a plan file, or a CI log.
  EOT
  value       = try(aws_db_instance.primary.master_user_secret[0].secret_arn, null)
}

output "security_group_id" {
  description = "The database security group, for granting access from a workload."
  value       = aws_security_group.this.id
}

output "dr_replica_endpoint" {
  description = "DR replica endpoint, or null when the replica is disabled."
  value       = try(aws_db_instance.dr_replica[0].endpoint, null)
}

output "dr_replica_identifier" {
  description = "DR replica identifier, used by the promotion runbook."
  value       = try(aws_db_instance.dr_replica[0].identifier, null)
}
