variable "environment" {
  description = "Environment name, prefixed to the cluster name."
  type        = string
}

variable "aws_partition" {
  description = "AWS partition, for building managed-policy ARNs."
  type        = string
  default     = "aws"
}

variable "vpc_id" {
  description = "VPC the cluster runs in."
  type        = string
}

variable "private_subnet_ids" {
  description = <<-EOT
    Private subnets for the control plane's network interfaces and the nodes.

    Private only. A node with a public address is a node exposed by default, and
    nothing here needs inbound internet traffic: the load balancer is internal
    and the API server is private.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "EKS requires subnets in at least two Availability Zones; three is strongly preferred."
  }
}

variable "kubernetes_version" {
  description = <<-EOT
    Kubernetes minor version.

    Pinned. EKS upgrades the patch level itself, but a MINOR upgrade can remove
    an API a workload still uses, so it is a planned change with a tested
    rollback rather than something that happens on a Tuesday.

    AWS supports a version for roughly 14 months; running past that moves the
    cluster to extended support and the bill goes up sharply.
  EOT
  type        = string
  default     = "1.31"

  validation {
    condition     = can(regex("^1\\.[0-9]{2}$", var.kubernetes_version))
    error_message = "kubernetes_version must be a minor version such as \"1.31\", never a patch version."
  }
}

variable "kms_key_arn" {
  description = "Customer managed key for Kubernetes Secret envelope encryption and node volume encryption."
  type        = string
}

variable "log_kms_key_arn" {
  description = "Customer managed key for the control plane log group. The audit log belongs to this platform, not to a default."
  type        = string
}

# -----------------------------------------------------------------------------
# API server exposure
# -----------------------------------------------------------------------------
variable "endpoint_public_access" {
  description = <<-EOT
    Expose the Kubernetes API server publicly.

    FALSE, and it should stay false. A public API server is protected only by its
    authentication being correct, forever, and it is scanned continuously. Reach
    a private cluster through a bastion, a VPN, or SSM Session Manager.

    If it must be true, endpoint_public_access_cidrs is then mandatory and cannot
    be the whole internet.
  EOT
  type        = bool
  default     = false
}

variable "endpoint_public_access_cidrs" {
  description = "Source ranges permitted to reach a public API server. Only consulted when endpoint_public_access is true."
  type        = list(string)
  default     = []

  validation {
    condition     = !contains(var.endpoint_public_access_cidrs, "0.0.0.0/0")
    error_message = "0.0.0.0/0 is not an access restriction. Name the ranges that genuinely need to reach the API server."
  }
}

# -----------------------------------------------------------------------------
# Access
# -----------------------------------------------------------------------------
variable "cluster_admin_role_arns" {
  description = <<-EOT
    IAM ROLES granted cluster-admin through EKS access entries.

    Roles, never users: a role can be assumed with MFA, and the session is
    time-bounded and appears in CloudTrail with the assuming identity.

    There is deliberately no default. bootstrap_cluster_creator_admin_permissions
    is false, so this list is the only way anyone reaches the cluster, and the
    validation below refuses an empty one -- a cluster nobody can administer is a
    cluster that has to be rebuilt.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.cluster_admin_role_arns) > 0
    error_message = "At least one administrator role is required, because the cluster creator is not granted admin automatically."
  }
}

variable "load_balancer_security_group_ids" {
  description = <<-EOT
    Security groups permitted to reach NodePort services.

    The internal load balancer's. Empty until that module exists, which means
    nothing outside the cluster can reach a workload -- the safe direction to be
    wrong in.
  EOT
  type        = list(string)
  default     = []
}

variable "admission_webhook_ports" {
  description = <<-EOT
    Ports on which admission webhooks listen, reachable from the control plane.

    Named explicitly rather than opening 1025-65535, which is what most examples
    do. That range is a holdover: the control plane needs the kubelet API and the
    webhook ports, which is a handful of ports rather than sixty-four thousand.
    It also spans 22 and 3389, which is what a scanner notices and a reviewer
    should.

    A webhook on a port not listed here fails with a timeout that looks like an
    application fault, so add the port when adding the webhook.
  EOT
  type        = list(number)
  default     = [443, 8443, 9443]
}

variable "node_port_range_start" {
  description = "First port in the Kubernetes NodePort range."
  type        = number
  default     = 30000
}

variable "node_port_range_end" {
  description = "Last port in the Kubernetes NodePort range."
  type        = number
  default     = 32767
}

# -----------------------------------------------------------------------------
# Nodes
# -----------------------------------------------------------------------------
variable "node_instance_types" {
  description = <<-EOT
    Instance types for the managed node group.

    Graviton by default: cheaper per unit of work, and the service images are
    built on a multi-architecture base. A list rather than one type gives the
    capacity pools something to fall back on.

    COST: billed per instance-hour, continuously. Nodes do not scale to zero.
  EOT
  type        = list(string)
  default     = ["m7g.large"]
}

variable "node_capacity_type" {
  description = <<-EOT
    ON_DEMAND or SPOT.

    ON_DEMAND. Spot is roughly 70% cheaper and can be reclaimed with two minutes'
    notice; for a platform whose workloads are durable and interruption-tolerant
    that is a reasonable trade to consider deliberately, in a node group of its
    own, rather than as a default here.
  EOT
  type        = string
  default     = "ON_DEMAND"

  validation {
    condition     = contains(["ON_DEMAND", "SPOT"], var.node_capacity_type)
    error_message = "node_capacity_type must be ON_DEMAND or SPOT."
  }
}

variable "node_desired_size" {
  description = "Nodes to start with. The cluster autoscaler owns this afterwards, and Terraform ignores changes to it."
  type        = number
  default     = 3
}

variable "node_min_size" {
  description = <<-EOT
    Minimum node count.

    Three, matching the Availability Zones. Below that, a zone failure can leave
    a service with no schedulable node in a surviving zone, which defeats the
    topology spread constraints in the Helm chart.
  EOT
  type        = number
  default     = 3

  validation {
    condition     = var.node_min_size >= 2
    error_message = "At least two nodes are required for a PodDisruptionBudget to be satisfiable during a rolling update."
  }
}

variable "node_max_size" {
  description = "Ceiling for autoscaling. A ceiling exists so a runaway is bounded rather than unbounded."
  type        = number
  default     = 6
}

variable "node_disk_size_gb" {
  description = "Root volume size. Container layers and emptyDir volumes live here, so it fills faster than people expect."
  type        = number
  default     = 50
}

variable "node_labels" {
  description = "Kubernetes labels applied to every node in the group."
  type        = map(string)
  default     = {}
}

variable "detailed_monitoring" {
  description = "One-minute EC2 metrics rather than five. COST: charged per instance."
  type        = bool
  default     = false
}

# -----------------------------------------------------------------------------
# Logging and add-ons
# -----------------------------------------------------------------------------
variable "enabled_cluster_log_types" {
  description = <<-EOT
    Control plane logs sent to CloudWatch.

    All five by default, and `audit` is the one that matters: it records who
    asked the API server for what, it is the first thing requested during an
    investigation, and it cannot be reconstructed afterwards if it was not being
    captured at the time.

    COST: on a busy cluster the audit log dominates CloudWatch Logs ingestion.
  EOT
  type        = list(string)
  default     = ["api", "audit", "authenticator", "controllerManager", "scheduler"]

  validation {
    condition     = contains(var.enabled_cluster_log_types, "audit")
    error_message = "The audit log is not optional. It is the record of who did what to the cluster."
  }
}

variable "log_retention_days" {
  description = "Retention for the control plane log group. EKS would otherwise create it with none at all."
  type        = number
  default     = 90
}

variable "addon_versions" {
  description = <<-EOT
    Managed add-ons, pinned by version.

    Pinned rather than tracking the default, so an add-on cannot change
    underneath a working cluster. Look the versions up for the Kubernetes minor
    version in use -- an add-on version is not portable across minor versions.

    THESE ARE PLACEHOLDERS and are not known to be correct for any particular
    Kubernetes version, because this has never been applied.
  EOT
  type        = map(string)
  default = {
    "vpc-cni"                = "v1.19.0-eksbuild.1"
    "coredns"                = "v1.11.3-eksbuild.1"
    "kube-proxy"             = "v1.31.0-eksbuild.2"
    "aws-ebs-csi-driver"     = "v1.37.0-eksbuild.1"
    "eks-pod-identity-agent" = "v1.3.4-eksbuild.1"
  }
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
