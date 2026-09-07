variable "environment" {
  description = "Environment name, prefixed to the cluster name."
  type        = string
}

variable "name" {
  description = "Short cluster name."
  type        = string
  default     = "los-events"
}

variable "vpc_id" {
  description = "VPC the brokers live in."
  type        = string
}

variable "private_subnet_ids" {
  description = <<-EOT
    Private subnets, one per Availability Zone.

    Three is the practical minimum. With fewer, a zone failure can take a
    majority of the cluster, and min.insync.replicas=2 then blocks every write.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "MSK requires at least two subnets; three across three Availability Zones is strongly preferred."
  }
}

variable "allowed_security_group_ids" {
  description = "Security groups permitted to reach the brokers on 9098."
  type        = list(string)
}

variable "kafka_version" {
  description = "Apache Kafka version. Matches the container used locally and in CI."
  type        = string
  default     = "3.9.x"
}

variable "number_of_broker_nodes" {
  description = <<-EOT
    Broker count. Must be a multiple of the subnet count.

    COST: brokers run continuously whether or not any message is published, and
    this is the largest term in the MSK bill.
  EOT
  type        = number
  default     = 3

  validation {
    condition     = var.number_of_broker_nodes >= 2
    error_message = "At least two brokers are required; three is the minimum for a replication factor of three."
  }
}

variable "broker_instance_type" {
  description = "Broker instance type. COST: multiplied by the broker count, continuously."
  type        = string
  default     = "kafka.m7g.large"
}

variable "broker_ebs_volume_size" {
  description = "Initial storage per broker, in gibibytes."
  type        = number
  default     = 100
}

variable "broker_ebs_max_volume_size" {
  description = "Storage autoscaling ceiling per broker, in gibibytes."
  type        = number
  default     = 1000
}

variable "storage_autoscaling_enabled" {
  description = <<-EOT
    Grow broker storage automatically.

    A broker that fills its disk stops accepting writes, and manual expansion
    takes long enough that noticing is already too late.
  EOT
  type        = bool
  default     = true
}

variable "storage_autoscaling_target_percent" {
  description = <<-EOT
    Utilisation that triggers expansion.

    70 rather than 90: an EBS expansion is not instantaneous, and starting at
    90% can still lose the race against a burst.
  EOT
  type        = number
  default     = 70
}

variable "provisioned_throughput_enabled" {
  description = "Provision EBS throughput rather than relying on the baseline. COST: billed per MiB/s."
  type        = bool
  default     = false
}

variable "provisioned_volume_throughput" {
  description = "Provisioned EBS throughput per broker in MiB/s."
  type        = number
  default     = 250
}

variable "min_insync_replicas" {
  description = <<-EOT
    Replicas that must acknowledge a write.

    2 with a replication factor of 3 means an acknowledged write survives losing
    one broker. 1 would acknowledge writes held on a single broker, and losing
    it loses them.
  EOT
  type        = number
  default     = 2

  validation {
    condition     = var.min_insync_replicas >= 2
    error_message = "min.insync.replicas must be at least 2, or an acknowledged write can be lost with a single broker."
  }
}

variable "default_replication_factor" {
  description = "Default replication factor for new topics."
  type        = number
  default     = 3
}

variable "log_retention_hours" {
  description = <<-EOT
    Topic retention.

    Seven days. The durable record is PostgreSQL and the audit trail; the log
    exists so a consumer can recover and replay, not as storage.
  EOT
  type        = number
  default     = 168
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for encryption at rest."
  type        = string
}

variable "enhanced_monitoring" {
  description = <<-EOT
    Monitoring level.

    PER_TOPIC_PER_PARTITION is what makes consumer lag visible per partition.
    Coarser levels report cluster totals, which cannot tell "one consumer is
    stuck" from "everything is slightly behind" -- and only the first is an
    incident.

    COST: more metrics, billed as custom CloudWatch metrics.
  EOT
  type        = string
  default     = "PER_TOPIC_PER_PARTITION"

  validation {
    condition = contains(
      ["DEFAULT", "PER_BROKER", "PER_TOPIC_PER_BROKER", "PER_TOPIC_PER_PARTITION"],
      var.enhanced_monitoring
    )
    error_message = "Invalid monitoring level."
  }
}

variable "broker_log_group_name" {
  description = "CloudWatch log group for broker logs."
  type        = string
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
  default     = {}
}
