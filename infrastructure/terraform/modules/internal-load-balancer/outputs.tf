output "arn" {
  description = <<-EOT
    Load balancer ARN.

    What the API Gateway VPC Link targets. A REST API's VPC Link accepts a
    NETWORK load balancer and nothing else, which is why this module exists
    alongside the ALB the Helm chart creates.
  EOT
  value       = aws_lb.this.arn
}

output "dns_name" {
  description = "Private DNS name. The API Gateway integration URI is built from this."
  value       = aws_lb.this.dns_name
}

output "integration_url" {
  description = <<-EOT
    The base URL for the API Gateway integration, scheme included.

    Built here rather than in the caller so the scheme cannot drift from whether
    the listener actually terminates TLS.
  EOT
  value       = "${var.enable_tls ? "https" : "http"}://${aws_lb.this.dns_name}"
}

output "security_group_id" {
  description = <<-EOT
    The load balancer's security group.

    Passed to the EKS module as a permitted source, so the nodes accept traffic
    from this load balancer and from nothing else.
  EOT
  value       = aws_security_group.this.id
}

output "target_group_arn" {
  description = <<-EOT
    Target group ARN.

    Referenced by a TargetGroupBinding when target_type is "ip" and pods are
    registered directly, rather than forwarding to a controller-managed ALB.
  EOT
  value       = aws_lb_target_group.this.arn
}

output "zone_id" {
  description = "Hosted zone id, for an alias record pointing at the load balancer."
  value       = aws_lb.this.zone_id
}
