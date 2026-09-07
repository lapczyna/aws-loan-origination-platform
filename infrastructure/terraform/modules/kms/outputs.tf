output "key_arn" {
  description = "Key ARN, for resources that encrypt with it."
  value       = aws_kms_key.this.arn
}

output "key_id" {
  description = "Key id."
  value       = aws_kms_key.this.key_id
}

output "alias_arn" {
  description = "Alias ARN."
  value       = aws_kms_alias.this.arn
}

output "alias_name" {
  description = "Alias name including the 'alias/' prefix."
  value       = aws_kms_alias.this.name
}
