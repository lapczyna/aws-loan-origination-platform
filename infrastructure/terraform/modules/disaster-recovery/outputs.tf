output "vault_arn" {
  description = "Primary backup vault ARN."
  value       = aws_backup_vault.primary.arn
}

output "vault_name" {
  description = "Primary backup vault name, for `aws backup list-recovery-points-by-backup-vault`."
  value       = aws_backup_vault.primary.name
}

output "dr_vault_arn" {
  description = "DR-region vault ARN, or null when cross-region copy is disabled."
  value       = try(aws_backup_vault.dr[0].arn, null)
}

output "plan_id" {
  description = "Backup plan id."
  value       = aws_backup_plan.this.id
}

output "backup_role_arn" {
  description = <<-EOT
    The role AWS Backup assumes.

    It can back up. It can only RESTORE when grant_restore_permissions is true,
    because a role that can restore can overwrite live data with an older copy.
  EOT
  value       = aws_iam_role.backup.arn
}
