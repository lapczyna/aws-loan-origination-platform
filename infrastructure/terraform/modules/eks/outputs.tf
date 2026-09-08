output "cluster_name" {
  description = "Cluster name. Referenced by the CloudWatch alarms and by `aws eks update-kubeconfig`."
  value       = aws_eks_cluster.this.name
}

output "cluster_arn" {
  description = "Cluster ARN, for scoping IAM policies."
  value       = aws_eks_cluster.this.arn
}

output "cluster_endpoint" {
  description = "API server endpoint. Private unless endpoint_public_access was deliberately enabled."
  value       = aws_eks_cluster.this.endpoint
}

output "cluster_certificate_authority_data" {
  description = "Base64 CA certificate for the API server, needed to build a kubeconfig."
  value       = aws_eks_cluster.this.certificate_authority[0].data
}

output "cluster_security_group_id" {
  description = "Control plane security group."
  value       = aws_security_group.cluster.id
}

output "node_security_group_id" {
  description = <<-EOT
    Worker node security group.

    THE OUTPUT THAT MATTERS TO THE REST OF THE PLATFORM. The RDS and MSK modules
    take it as an allowed source, so database and broker access follows the
    workload rather than an address range that something else may occupy later.
  EOT
  value       = aws_security_group.nodes.id
}

output "node_role_arn" {
  description = "Node IAM role. Deliberately carries no application permission; those are granted per service through IRSA."
  value       = aws_iam_role.nodes.arn
}

output "oidc_provider_arn" {
  description = <<-EOT
    IAM OIDC provider for IRSA.

    Every per-service role's trust policy names this, which is what lets a pod
    assume a role through its service account instead of inheriting the node's.
  EOT
  value       = aws_iam_openid_connect_provider.this.arn
}

output "oidc_provider_url" {
  description = "OIDC issuer URL, without the scheme, as an IRSA trust policy condition expects it."
  value       = replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")
}

output "cluster_log_group_name" {
  description = "Control plane log group, including the audit log."
  value       = aws_cloudwatch_log_group.cluster.name
}

output "addon_role_arns" {
  description = <<-EOT
    Add-on name to IRSA role ARN, for annotating the add-on charts' service
    accounts in kube-system.

    Empty when enable_addon_roles is false.
  EOT
  value = var.enable_addon_roles ? {
    "aws-load-balancer-controller" = aws_iam_role.load_balancer_controller[0].arn
    "cluster-autoscaler"           = aws_iam_role.cluster_autoscaler[0].arn
    "ebs-csi-controller-sa"        = aws_iam_role.ebs_csi_driver[0].arn
  } : {}
}
