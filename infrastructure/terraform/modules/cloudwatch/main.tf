# =============================================================================
# CloudWatch alarms, log groups and the alarm topic.
#
# WHAT IS ALARMED AND WHY
# -----------------------
# Every alarm below corresponds to something a customer would notice. Alarms
# that fire on things nobody would notice are how a team learns to ignore
# alarms, and an ignored alarm is worse than no alarm because it creates the
# impression of coverage.
#
# The business alarms matter as much as the technical ones. "The outbox has
# stopped draining" is invisible to CPU and latency dashboards and yet means
# every submitted application has silently stopped progressing.
#
# NO NOTIFICATION ENDPOINT IS CONFIGURED HERE. An email address or a phone
# number in a repository is personal data, and a subscription created by
# Terraform sends a confirmation email to a real person the moment it is
# applied. Subscriptions are added at deployment time.
#
# NOTHING HERE HAS BEEN APPLIED.
# =============================================================================

locals {
  prefix      = "${var.environment}-los"
  common_tags = merge(var.tags, { Module = "cloudwatch" })

  # Every alarm points here. A single topic means one place to add a
  # subscription and one place to silence during planned maintenance.
  alarm_actions = [aws_sns_topic.alarms.arn]
}

# -----------------------------------------------------------------------------
# Alarm topic.
# -----------------------------------------------------------------------------
resource "aws_sns_topic" "alarms" {
  name              = "${local.prefix}-alarms"
  kms_master_key_id = var.kms_key_arn

  tags = merge(local.common_tags, { Name = "${local.prefix}-alarms" })
}

# Only CloudWatch may publish. Without this, any principal in the account can
# publish to the topic and fabricate an alarm -- or, more likely, flood it.
data "aws_iam_policy_document" "alarms_topic" {
  statement {
    sid    = "AllowCloudWatchAlarmsToPublish"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }

    actions   = ["SNS:Publish"]
    resources = [aws_sns_topic.alarms.arn]

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceAccount"
      values   = [var.account_id]
    }
  }
}

resource "aws_sns_topic_policy" "alarms" {
  arn    = aws_sns_topic.alarms.arn
  policy = data.aws_iam_policy_document.alarms_topic.json
}

# NOTE: there is deliberately no aws_sns_topic_subscription resource here.
# See the module header.

# -----------------------------------------------------------------------------
# Log groups.
#
# Created explicitly rather than implicitly by the services that write to them.
# A log group created on first write has no retention policy and no KMS key,
# which means logs are kept forever, unencrypted, and billed forever.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "services" {
  for_each = toset(var.service_names)

  name              = "/aws/eks/${var.environment}/${each.value}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn

  tags = merge(local.common_tags, { Name = each.value })
}

resource "aws_cloudwatch_log_group" "msk_broker" {
  name              = "/aws/msk/${local.prefix}"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn

  tags = local.common_tags
}

resource "aws_cloudwatch_log_group" "api_gateway_access" {
  name = "/aws/apigateway/${local.prefix}-access"
  # Access logs are the record of who called what, and are the first thing
  # requested during a security investigation. Retained longer than application
  # logs for that reason.
  retention_in_days = var.access_log_retention_days
  kms_key_id        = var.kms_key_arn

  tags = local.common_tags
}

# WAF logs. The name MUST begin with "aws-waf-logs-": WAF refuses any other
# destination, and the error it returns does not explain why.
#
# These records include the matched rule and the request's headers, so the log
# group is itself sensitive. It is encrypted with the same customer managed key
# and its retention is bounded; the header values that would matter most are
# redacted at the WAF rather than here.
resource "aws_cloudwatch_log_group" "waf" {
  name              = "aws-waf-logs-${local.prefix}"
  retention_in_days = var.access_log_retention_days
  kms_key_id        = var.kms_key_arn

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# API Gateway alarms: what a customer experiences at the edge.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "api_5xx" {
  alarm_name = "${local.prefix}-api-5xx-elevated"
  alarm_description = join(" ", [
    "The API is returning server errors. Customers are seeing failures.",
    "Runbook: docs/operations/runbooks/api-errors.md"
  ])

  namespace   = "AWS/ApiGateway"
  metric_name = "5XXError"
  statistic   = "Sum"

  dimensions = {
    ApiName = var.api_gateway_name
    Stage   = var.api_gateway_stage
  }

  period              = 300
  evaluation_periods  = 2
  threshold           = var.api_5xx_threshold
  comparison_operator = "GreaterThanThreshold"

  # Missing data is NOT breaching. No traffic means no errors, and treating
  # silence as failure produces an alarm every night.
  treat_missing_data = "notBreaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "api_latency_p99" {
  alarm_name = "${local.prefix}-api-latency-p99"
  alarm_description = join(" ", [
    "The slowest 1% of requests exceed the latency objective.",
    "p99 rather than average: an average hides the tail entirely, and the tail",
    "is what customers complain about.",
    "Runbook: docs/operations/runbooks/api-latency.md"
  ])

  namespace   = "AWS/ApiGateway"
  metric_name = "Latency"
  # p99, not Average. An average of 200ms is perfectly compatible with 1% of
  # requests taking thirty seconds.
  extended_statistic = "p99"

  dimensions = {
    ApiName = var.api_gateway_name
    Stage   = var.api_gateway_stage
  }

  period              = 300
  evaluation_periods  = 3
  threshold           = var.api_latency_p99_threshold_ms
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "api_4xx" {
  alarm_name = "${local.prefix}-api-4xx-elevated"
  alarm_description = join(" ", [
    "Client errors are elevated. Usually a broken client or a partner using the",
    "API incorrectly; occasionally credential stuffing or enumeration.",
    "Runbook: docs/operations/runbooks/api-errors.md"
  ])

  namespace   = "AWS/ApiGateway"
  metric_name = "4XXError"
  statistic   = "Sum"

  dimensions = {
    ApiName = var.api_gateway_name
    Stage   = var.api_gateway_stage
  }

  period              = 300
  evaluation_periods  = 3
  threshold           = var.api_4xx_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# RDS alarms.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "rds_cpu" {
  alarm_name        = "${local.prefix}-rds-cpu-high"
  alarm_description = "Database CPU is sustained high. Runbook: docs/operations/runbooks/rds-saturation.md"

  namespace   = "AWS/RDS"
  metric_name = "CPUUtilization"
  statistic   = "Average"

  dimensions = {
    DBInstanceIdentifier = var.rds_instance_identifier
  }

  period = 300
  # Three periods, because a single spike during a backup or a vacuum is normal
  # and paging on it teaches people to ignore the alarm.
  evaluation_periods  = 3
  threshold           = 80
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "rds_free_storage" {
  alarm_name = "${local.prefix}-rds-storage-low"
  alarm_description = join(" ", [
    "Free storage is low. A database that fills its volume stops accepting",
    "writes entirely, which is a full outage, not a degradation.",
    "Runbook: docs/operations/runbooks/rds-saturation.md"
  ])

  namespace   = "AWS/RDS"
  metric_name = "FreeStorageSpace"
  statistic   = "Average"

  dimensions = {
    DBInstanceIdentifier = var.rds_instance_identifier
  }

  period              = 300
  evaluation_periods  = 1
  threshold           = var.rds_free_storage_threshold_bytes
  comparison_operator = "LessThanThreshold"
  # Missing data IS breaching here. Not receiving the metric can mean the
  # instance is unreachable, which is at least as serious as low storage.
  treat_missing_data = "breaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "rds_connections" {
  alarm_name = "${local.prefix}-rds-connections-high"
  alarm_description = join(" ", [
    "Connection count is approaching the limit. Usually a pod count that has",
    "outgrown the database's max_connections, or a connection leak.",
    "Runbook: docs/operations/runbooks/rds-saturation.md"
  ])

  namespace   = "AWS/RDS"
  metric_name = "DatabaseConnections"
  statistic   = "Maximum"

  dimensions = {
    DBInstanceIdentifier = var.rds_instance_identifier
  }

  period              = 300
  evaluation_periods  = 2
  threshold           = var.rds_connection_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "rds_replica_lag" {
  count = var.enable_cross_region_dr_replica ? 1 : 0

  alarm_name = "${local.prefix}-rds-dr-replica-lag"
  alarm_description = join(" ", [
    "The disaster-recovery replica is falling behind. Replica lag IS the",
    "recovery point objective: whatever has not replicated when the primary is",
    "lost is lost with it.",
    "Runbook: docs/operations/runbooks/cross-region-replica-promotion.md"
  ])

  namespace   = "AWS/RDS"
  metric_name = "ReplicaLag"
  statistic   = "Maximum"

  dimensions = {
    DBInstanceIdentifier = var.rds_dr_replica_identifier
  }

  period              = 300
  evaluation_periods  = 2
  threshold           = var.rds_replica_lag_threshold_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# MSK alarms.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "kafka_consumer_lag" {
  alarm_name = "${local.prefix}-kafka-consumer-lag"
  alarm_description = join(" ", [
    "A consumer group is falling behind. Applications are being assessed late,",
    "or not at all.",
    "Runbook: docs/operations/runbooks/kafka-consumer-lag.md"
  ])

  namespace   = "AWS/Kafka"
  metric_name = "SumOffsetLag"
  statistic   = "Maximum"

  dimensions = {
    "Cluster Name" = var.msk_cluster_name
  }

  period              = 300
  evaluation_periods  = 3
  threshold           = var.kafka_consumer_lag_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "kafka_under_replicated" {
  alarm_name = "${local.prefix}-kafka-under-replicated-partitions"
  alarm_description = join(" ", [
    "Partitions are under-replicated. With min.insync.replicas=2 this is one",
    "broker away from writes being REFUSED, so it is urgent even though nothing",
    "has failed yet.",
    "Runbook: docs/operations/runbooks/kafka-under-replicated.md"
  ])

  namespace   = "AWS/Kafka"
  metric_name = "UnderReplicatedPartitions"
  statistic   = "Maximum"

  dimensions = {
    "Cluster Name" = var.msk_cluster_name
  }

  period             = 300
  evaluation_periods = 1
  # Any under-replicated partition at all.
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "breaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# Business alarms.
#
# These are the ones that matter most and the ones most often missing. Every
# metric here is published by the services themselves; none is visible to an
# infrastructure dashboard.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "outbox_oldest_age" {
  alarm_name = "${local.prefix}-outbox-oldest-record-age"
  alarm_description = join(" ", [
    "An event has been waiting in the outbox too long. THE most useful outbox",
    "signal: backlog size alone is ambiguous because a large backlog draining",
    "quickly is healthy, but a rising oldest-record age always means events are",
    "stuck -- and a stuck event is an application whose assessment never",
    "started.",
    "Runbook: docs/operations/runbooks/stuck-outbox.md"
  ])

  namespace   = var.application_metric_namespace
  metric_name = "los.outbox.oldest.age.seconds"
  statistic   = "Maximum"

  period              = 60
  evaluation_periods  = 3
  threshold           = var.outbox_age_threshold_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "outbox_backlog" {
  alarm_name        = "${local.prefix}-outbox-backlog"
  alarm_description = "The outbox backlog is large. Read alongside the oldest-record-age alarm: backlog without rising age is throughput, backlog with rising age is a stall. Runbook: docs/operations/runbooks/stuck-outbox.md"

  namespace   = var.application_metric_namespace
  metric_name = "los.outbox.pending"
  statistic   = "Maximum"

  period              = 300
  evaluation_periods  = 2
  threshold           = var.outbox_backlog_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "dead_letter_events" {
  alarm_name = "${local.prefix}-dead-letter-events"
  alarm_description = join(" ", [
    "An event exhausted its retry budget and needs a human. Any non-zero value",
    "means work has been abandoned and will not resume on its own.",
    "Runbook: docs/operations/runbooks/event-replay.md"
  ])

  namespace   = var.application_metric_namespace
  metric_name = "los.outbox.permanently.failed"
  statistic   = "Maximum"

  period             = 300
  evaluation_periods = 1
  # Any at all.
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "applications_stuck" {
  alarm_name = "${local.prefix}-applications-stuck-in-processing"
  alarm_description = join(" ", [
    "Applications have been mid-assessment for too long. Every other alarm can",
    "be quiet while this fires: the platform is up, the queues are empty, and",
    "customers are still waiting.",
    "Runbook: docs/operations/runbooks/failed-workflow.md"
  ])

  namespace   = var.application_metric_namespace
  metric_name = "los.applications.stuck"
  statistic   = "Maximum"

  period              = 300
  evaluation_periods  = 2
  threshold           = var.stuck_applications_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions
  ok_actions    = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "manual_review_backlog" {
  alarm_name = "${local.prefix}-manual-review-backlog"
  alarm_description = join(" ", [
    "The manual-review queue is growing faster than reviewers are clearing it.",
    "An operational alarm rather than a technical one: nothing is broken, and",
    "customers are still waiting longer than they should.",
    "Runbook: docs/operations/runbooks/manual-review-backlog.md"
  ])

  namespace   = var.application_metric_namespace
  metric_name = "los.manual.review.pending"
  statistic   = "Maximum"

  period              = 900
  evaluation_periods  = 2
  threshold           = var.manual_review_backlog_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "workflow_latency" {
  alarm_name        = "${local.prefix}-workflow-processing-latency"
  alarm_description = "Assessments are taking longer than the objective. Usually a slow external provider. Runbook: docs/operations/runbooks/failed-workflow.md"

  namespace          = var.application_metric_namespace
  metric_name        = "los.workflow.duration.seconds"
  extended_statistic = "p95"

  period              = 300
  evaluation_periods  = 3
  threshold           = var.workflow_latency_p95_threshold_seconds
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions

  tags = local.common_tags
}

resource "aws_cloudwatch_metric_alarm" "readiness_failures" {
  alarm_name        = "${local.prefix}-application-readiness-failures"
  alarm_description = "Pods are failing their readiness probes, so capacity is reduced or absent. Runbook: docs/operations/runbooks/eks-rollback.md"

  namespace   = "ContainerInsights"
  metric_name = "pod_number_of_container_restarts"
  statistic   = "Sum"

  dimensions = {
    ClusterName = var.eks_cluster_name
  }

  period              = 300
  evaluation_periods  = 2
  threshold           = var.pod_restart_threshold
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"

  alarm_actions = local.alarm_actions

  tags = local.common_tags
}
