variable "environment" {
  description = "Environment name, prefixed to every resource."
  type        = string
}

variable "region" {
  description = "AWS region. Used to build VPC endpoint service names."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR. A /16 leaves room for three tiers across three Availability Zones."
  type        = string
  default     = "10.0.0.0/16"
}

variable "availability_zones" {
  description = <<-EOT
    Availability Zones to span.

    Three, not two. With two, losing one leaves a single zone, and no quorum --
    MSK's min.insync.replicas, etcd, a control plane -- can be met.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.availability_zones) >= 2
    error_message = "At least two Availability Zones are required; three is strongly preferred."
  }
}

variable "single_nat_gateway" {
  description = <<-EOT
    Share one NAT gateway across all Availability Zones.

    COST: a NAT gateway is billed hourly plus per gigabyte processed, so one per
    zone triples the hourly charge. Sharing one makes a single zone failure take
    down egress everywhere, which is why this is false by default and only
    appropriate in development.
  EOT
  type        = bool
  default     = false
}

variable "enable_flow_logs" {
  description = <<-EOT
    Capture VPC flow logs.

    COST: billed as CloudWatch Logs ingestion, and a busy VPC produces a lot.
    Worth it regardless: flow logs cannot be reconstructed after the fact.
  EOT
  type        = bool
  default     = true
}

variable "flow_log_retention_days" {
  description = "Flow log retention in days."
  type        = number
  default     = 30
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for the flow log group."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
  default     = {}
}
