output "repository_urls" {
  description = "Repository URLs, keyed by service name."
  value       = { for name, repo in aws_ecr_repository.this : name => repo.repository_url }
}

output "repository_arns" {
  description = "Repository ARNs, for scoping a push policy in CI."
  value       = { for name, repo in aws_ecr_repository.this : name => repo.arn }
}

output "registry_id" {
  description = "Registry id, which is the account id."
  value       = values(aws_ecr_repository.this)[0].registry_id
}
