output "applicant_pepper_secret_arn" {
  description = "The pepper's ARN. Its value is placed out of band and is never read by Terraform."
  value       = aws_secretsmanager_secret.applicant_pepper.arn
}

output "applicant_pepper_secret_name" {
  description = "The pepper's name, matching the chart's mount."
  value       = aws_secretsmanager_secret.applicant_pepper.name
}
