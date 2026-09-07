output "cluster_arn" {
  description = "Cluster ARN, used to scope IAM policies and alarms."
  value       = aws_msk_cluster.this.arn
}

output "cluster_name" {
  description = "Cluster name."
  value       = aws_msk_cluster.this.cluster_name
}

output "bootstrap_brokers_sasl_iam" {
  description = <<-EOT
    Bootstrap brokers for IAM-authenticated TLS connections.

    The only endpoint clients should use. The plaintext and SCRAM endpoints are
    deliberately never enabled.
  EOT
  value       = aws_msk_cluster.this.bootstrap_brokers_sasl_iam
}

output "security_group_id" {
  description = "Broker security group, for granting a workload access."
  value       = aws_security_group.brokers.id
}

output "zookeeper_connect_string" {
  description = <<-EOT
    Deliberately empty.

    This cluster runs in KRaft mode and has no ZooKeeper. The output exists so a
    caller expecting the legacy attribute gets an obvious empty value rather
    than a confusing plan error.
  EOT
  value       = ""
}
