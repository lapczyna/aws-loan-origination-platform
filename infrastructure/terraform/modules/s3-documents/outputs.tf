output "bucket_name" {
  description = "Bucket name."
  value       = aws_s3_bucket.this.id
}

output "bucket_arn" {
  description = "Bucket ARN, for scoping IAM policies."
  value       = aws_s3_bucket.this.arn
}

output "bucket_regional_domain_name" {
  description = "Regional domain name, used when presigning."
  value       = aws_s3_bucket.this.bucket_regional_domain_name
}

output "quarantine_prefix_arn" {
  description = <<-EOT
    ARN pattern for the quarantine area.

    Lets a policy grant the scanner read access to unscanned objects while
    denying it to everything else -- the separation the two prefixes exist for.
  EOT
  value       = "${aws_s3_bucket.this.arn}/quarantine/*"
}

output "accepted_prefix_arn" {
  description = "ARN pattern for the accepted area."
  value       = "${aws_s3_bucket.this.arn}/accepted/*"
}
