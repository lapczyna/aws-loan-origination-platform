output "bucket_name" {
  description = "Bucket name."
  value       = aws_s3_bucket.this.id
}

output "bucket_arn" {
  description = "Bucket ARN, for scoping the audit service's write policy."
  value       = aws_s3_bucket.this.arn
}

output "object_lock_enabled" {
  description = "Whether Object Lock is active, so the compliance posture is auditable from state."
  value       = var.object_lock_enabled
}
