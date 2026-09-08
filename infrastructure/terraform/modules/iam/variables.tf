variable "environment" {
  description = "Environment name. Role names are \"<environment>-<service>\", matching the ARN the Helm chart's ServiceAccount annotation builds."
  type        = string
}

variable "aws_partition" {
  description = "AWS partition, for constructing ARNs."
  type        = string
  default     = "aws"
}

variable "region" {
  description = "Region, for constructing ARNs and ViaService conditions."
  type        = string
}

variable "account_id" {
  description = "Account id, for constructing ARNs. Supplied by the caller from a data source, never committed."
  type        = string
}

# -----------------------------------------------------------------------------
# Where the pods run
# -----------------------------------------------------------------------------
variable "eks_cluster_name" {
  description = "Cluster the Pod Identity associations are created in."
  type        = string
}

variable "kubernetes_namespace" {
  description = "Namespace the services run in. Part of the association, so a pod in another namespace cannot use these roles."
  type        = string
  default     = "loan-origination"
}

variable "helm_release_name" {
  description = <<-EOT
    Helm release name.

    The chart names its service accounts "<release>-<service>", and the Pod
    Identity association must name the service account exactly. A mismatch fails
    open in the confusing direction: the pod starts, has no credentials, and the
    first AWS call fails with an authorisation error that looks like a policy
    problem.
  EOT
  type        = string
  default     = "loan-origination-platform"
}

# -----------------------------------------------------------------------------
# What the services reach
# -----------------------------------------------------------------------------
variable "db_resource_id" {
  description = <<-EOT
    The RDS instance's resource id, for scoping rds-db:connect.

    The RESOURCE ID, not the identifier: an ARN built from the name grants
    nothing, and the failure looks like a network problem.
  EOT
  type        = string
}

variable "msk_cluster_arn" {
  description = "MSK cluster ARN. Topic and consumer-group ARNs are derived from it by substituting the resource type."
  type        = string
}

variable "documents_bucket_arn" {
  description = "Documents bucket. Only the document service is granted access to it."
  type        = string
}

variable "audit_bucket_arn" {
  description = "Audit bucket. Only the audit service is granted access to it, and only to append."
  type        = string
}

variable "documents_kms_key_arn" {
  description = "Key encrypting uploaded documents."
  type        = string
}

variable "audit_kms_key_arn" {
  description = "Key encrypting the audit trail. Deliberately separate from the documents key, so \"who can decrypt the audit trail\" is answerable on its own."
  type        = string
}

variable "secrets_kms_key_arn" {
  description = "Key encrypting the Secrets Manager entries the services mount."
  type        = string
}

variable "metric_namespace" {
  description = <<-EOT
    CloudWatch namespace the services may publish to.

    PutMetricData cannot be scoped by resource -- the API has none -- so it is
    scoped by this condition instead. Without it a compromised service could
    write into any namespace, including the ones the alarms read, and fabricate
    a healthy platform.
  EOT
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
