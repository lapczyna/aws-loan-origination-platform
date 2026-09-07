variable "environment" {
  description = "Environment name, prefixed to every alarm and log group."
  type        = string
}

variable "account_id" {
  description = "Account id, used to scope the SNS topic policy to this account's CloudWatch."
  type        = string
}

variable "kms_key_arn" {
  description = "Customer managed KMS key for the alarm topic and the log groups."
  type        = string
}

variable "service_names" {
  description = "Services that get a log group."
  type        = list(string)
  default     = ["application-service", "document-service", "workflow-service", "audit-service"]
}

variable "log_retention_days" {
  description = <<-EOT
    Application log retention.

    COST: CloudWatch Logs is billed on ingestion AND storage, and indefinite
    retention is a bill that only ever grows.
  EOT
  type        = number
  default     = 30
}

variable "access_log_retention_days" {
  description = <<-EOT
    API Gateway access log retention.

    Longer than application logs: access logs are the record of who called what
    and are the first thing requested in a security investigation.
  EOT
  type        = number
  default     = 90
}

# --- Edge --------------------------------------------------------------------

variable "api_gateway_name" {
  description = "API Gateway name, used as an alarm dimension."
  type        = string
}

variable "api_gateway_stage" {
  description = "API Gateway stage name."
  type        = string
}

variable "api_5xx_threshold" {
  description = "Server errors in a five-minute window before alarming."
  type        = number
  default     = 5
}

variable "api_4xx_threshold" {
  description = "Client errors in a five-minute window before alarming. Higher than 5xx: some client error is normal."
  type        = number
  default     = 100
}

variable "api_latency_p99_threshold_ms" {
  description = "p99 latency objective in milliseconds."
  type        = number
  default     = 3000
}

# --- RDS ---------------------------------------------------------------------

variable "rds_instance_identifier" {
  description = "Primary database identifier."
  type        = string
}

variable "rds_free_storage_threshold_bytes" {
  description = "Free storage below which to alarm. 10 GiB by default."
  type        = number
  default     = 10737418240
}

variable "rds_connection_threshold" {
  description = <<-EOT
    Connection count to alarm on.

    Should be set below the instance's max_connections, which scales with
    instance memory. Too close to the limit and the alarm fires only once
    connections are already being refused.
  EOT
  type        = number
  default     = 80
}

variable "enable_cross_region_dr_replica" {
  description = "Whether the DR replica exists, and therefore whether its lag alarm is created."
  type        = bool
  default     = false
}

variable "rds_dr_replica_identifier" {
  description = "DR replica identifier."
  type        = string
  default     = null
}

variable "rds_replica_lag_threshold_seconds" {
  description = <<-EOT
    Replica lag to alarm on.

    Replica lag IS the recovery point objective. This threshold is a statement
    about how much data the business accepts losing in a regional failure.
  EOT
  type        = number
  default     = 300
}

# --- MSK ---------------------------------------------------------------------

variable "msk_cluster_name" {
  description = "MSK cluster name, used as an alarm dimension."
  type        = string
}

variable "kafka_consumer_lag_threshold" {
  description = "Consumer offset lag to alarm on."
  type        = number
  default     = 10000
}

# --- EKS ---------------------------------------------------------------------

variable "eks_cluster_name" {
  description = "EKS cluster name, used as a Container Insights dimension."
  type        = string
}

variable "pod_restart_threshold" {
  description = "Container restarts in a five-minute window before alarming."
  type        = number
  default     = 5
}

# --- Business ----------------------------------------------------------------

variable "application_metric_namespace" {
  description = "CloudWatch namespace the services publish business metrics to."
  type        = string
}

variable "outbox_age_threshold_seconds" {
  description = <<-EOT
    Age of the oldest unpublished outbox record before alarming.

    Five minutes. Beyond that an application's assessment has demonstrably not
    started, which is customer-visible.
  EOT
  type        = number
  default     = 300
}

variable "outbox_backlog_threshold" {
  description = "Pending outbox records before alarming."
  type        = number
  default     = 1000
}

variable "stuck_applications_threshold" {
  description = "Applications mid-assessment beyond the expected window before alarming."
  type        = number
  default     = 10
}

variable "manual_review_backlog_threshold" {
  description = "Applications awaiting a human decision before alarming."
  type        = number
  default     = 50
}

variable "workflow_latency_p95_threshold_seconds" {
  description = "p95 assessment duration objective, in seconds."
  type        = number
  default     = 120
}

variable "tags" {
  description = "Tags applied to every resource."
  type        = map(string)
  default     = {}
}
