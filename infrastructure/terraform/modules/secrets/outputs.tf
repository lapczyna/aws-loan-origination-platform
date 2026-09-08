output "database_secret_arns" {
  description = "Service name to secret ARN. The values are NOT outputs, because this module never knows them."
  value       = { for name, secret in aws_secretsmanager_secret.database : name => secret.arn }
}

output "database_secret_names" {
  description = "Service name to secret name, matching what the chart's SecretProviderClass mounts."
  value       = { for name, secret in aws_secretsmanager_secret.database : name => secret.name }
}

output "applicant_pepper_secret_arn" {
  description = "The pepper's ARN. Its value is placed out of band and is never read by Terraform."
  value       = aws_secretsmanager_secret.applicant_pepper.arn
}

output "applicant_pepper_secret_name" {
  description = "The pepper's name, matching the chart's mount."
  value       = aws_secretsmanager_secret.applicant_pepper.name
}
