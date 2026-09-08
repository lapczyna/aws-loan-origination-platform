output "role_arns" {
  description = <<-EOT
    Service name to role ARN.

    The chart builds the same ARNs from its account-id placeholder rather than
    consuming this, so the two must agree on the naming convention:
    "<environment>-<service>". This output is what proves what was actually
    created.
  EOT
  value       = { for name, role in aws_iam_role.service : name => role.arn }
}

output "role_names" {
  description = "Service name to role name, for attaching an additional policy from outside this module."
  value       = { for name, role in aws_iam_role.service : name => role.name }
}

output "pod_identity_association_ids" {
  description = "Service name to Pod Identity association id, for confirming the binding exists."
  value       = { for name, association in aws_eks_pod_identity_association.service : name => association.association_id }
}
