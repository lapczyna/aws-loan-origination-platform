# =============================================================================
# Amazon MSK.
#
# IAM AUTHENTICATION, NOT SASL/SCRAM
# ----------------------------------
# MSK supports SASL/SCRAM with usernames and passwords stored in Secrets
# Manager. This module does not use it, and the configuration below actively
# disables it.
#
# A SCRAM credential is a long-lived password: it has to be created, stored,
# distributed to every consumer, rotated on a schedule someone maintains, and
# revoked when a service is decommissioned. Each of those is a step that gets
# skipped. IAM authentication has no password at all -- the pod's role IS the
# credential, it expires on its own, it is revoked by changing a policy, and
# every connection appears in CloudTrail attributed to a principal.
#
# NOTHING HERE HAS BEEN APPLIED.
#
# COST WARNING: MSK is one of the two largest line items on this platform (the
# other is EKS). Three brokers run continuously whether or not any message is
# published. See docs/operations/cost.md.
# =============================================================================

locals {
  cluster_name = "${var.environment}-${var.name}"
  common_tags  = merge(var.tags, { Module = "msk" })
}

# -----------------------------------------------------------------------------
# Security group. Brokers are private and reachable only from named workloads.
# -----------------------------------------------------------------------------
resource "aws_security_group" "brokers" {
  name        = "${local.cluster_name}-brokers"
  description = "MSK brokers for ${local.cluster_name}"
  vpc_id      = var.vpc_id

  tags = merge(local.common_tags, { Name = "${local.cluster_name}-brokers" })

  lifecycle {
    create_before_destroy = true
  }
}

# 9098 is the IAM-authenticated TLS port. The plaintext port (9092) and the
# SCRAM port (9096) are deliberately never opened.
resource "aws_vpc_security_group_ingress_rule" "iam_tls" {
  for_each = toset(var.allowed_security_group_ids)

  security_group_id            = aws_security_group.brokers.id
  referenced_security_group_id = each.value
  from_port                    = 9098
  to_port                      = 9098
  ip_protocol                  = "tcp"
  description                  = "Kafka over TLS with IAM authentication"

  tags = local.common_tags
}

# Brokers talk to each other.
resource "aws_vpc_security_group_ingress_rule" "inter_broker" {
  security_group_id            = aws_security_group.brokers.id
  referenced_security_group_id = aws_security_group.brokers.id
  from_port                    = 9098
  to_port                      = 9098
  ip_protocol                  = "tcp"
  description                  = "Inter-broker replication"

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# Cluster configuration.
#
# These are durability decisions, and the defaults are wrong for a system of
# record.
# -----------------------------------------------------------------------------
resource "aws_msk_configuration" "this" {
  name           = "${local.cluster_name}-config"
  kafka_versions = [var.kafka_version]

  description = "Durability-oriented configuration for ${local.cluster_name}"

  server_properties = <<-PROPERTIES
    # A topic created by accident is a topic nobody is reading. Automatic
    # creation turns a typo in a topic name into silent message loss rather
    # than a startup failure, so it is off.
    auto.create.topics.enable=false

    # Deleting a topic is a deliberate administrative act, not something an
    # application should be able to do.
    delete.topic.enable=false

    # Two of three replicas must acknowledge a write. With acks=all on the
    # producer, this is what makes an acknowledged write survive the loss of one
    # broker. min.insync.replicas=1 would acknowledge writes that exist on a
    # single broker, and a broker failure would lose them.
    min.insync.replicas=${var.min_insync_replicas}

    # Three copies of every partition, one per Availability Zone.
    default.replication.factor=${var.default_replication_factor}

    # Off. Unclean election allows an out-of-sync replica to become leader,
    # which recovers availability by DISCARDING acknowledged writes. For a
    # lending platform that is never the right trade.
    unclean.leader.election.enable=false

    # Seven days. The durable record is PostgreSQL and the audit trail; the log
    # exists so a consumer can recover and replay, not as storage.
    log.retention.hours=${var.log_retention_hours}

    # Compression at the broker, so a producer that forgets to compress does not
    # multiply the storage bill.
    compression.type=producer
  PROPERTIES

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# The cluster.
# -----------------------------------------------------------------------------
resource "aws_msk_cluster" "this" {
  cluster_name           = local.cluster_name
  kafka_version          = var.kafka_version
  number_of_broker_nodes = var.number_of_broker_nodes

  broker_node_group_info {
    instance_type = var.broker_instance_type
    # One subnet per Availability Zone. Fewer than three means a zone failure
    # can take a majority of the cluster, and min.insync.replicas=2 then blocks
    # every write.
    client_subnets  = var.private_subnet_ids
    security_groups = [aws_security_group.brokers.id]

    storage_info {
      ebs_storage_info {
        volume_size = var.broker_ebs_volume_size

        # Storage grows on its own before a broker fills up. A full broker stops
        # accepting writes, and expanding storage manually takes long enough
        # that the outage is real.
        dynamic "provisioned_throughput" {
          for_each = var.provisioned_throughput_enabled ? [1] : []
          content {
            enabled           = true
            volume_throughput = var.provisioned_volume_throughput
          }
        }
      }
    }
  }

  # -- Authentication ----------------------------------------------------
  client_authentication {
    sasl {
      # IAM only. No password exists to leak, rotate or forget to revoke.
      iam = true
      # Explicitly off: SCRAM would reintroduce exactly the long-lived
      # credential this design avoids.
      scram = false
    }
    # Unauthenticated access is never enabled, in any environment.
    unauthenticated = false
  }

  # -- Encryption --------------------------------------------------------
  encryption_info {
    encryption_at_rest_kms_key_arn = var.kms_key_arn

    encryption_in_transit {
      # TLS only for clients. PLAINTEXT and TLS_PLAINTEXT both permit an
      # unencrypted connection, and a client will happily choose it.
      client_broker = "TLS"
      # Replication between brokers crosses Availability Zones, which means it
      # crosses the network. Encrypting it is not optional.
      in_cluster = true
    }
  }

  configuration_info {
    arn      = aws_msk_configuration.this.arn
    revision = aws_msk_configuration.this.latest_revision
  }

  # -- Monitoring --------------------------------------------------------
  # PER_TOPIC_PER_PARTITION is what makes consumer lag visible per partition.
  # The cheaper levels report cluster totals, which cannot distinguish "one
  # consumer is stuck" from "everything is slightly behind" -- and the first is
  # an incident while the second is not.
  enhanced_monitoring = var.enhanced_monitoring

  open_monitoring {
    prometheus {
      jmx_exporter {
        enabled_in_broker = true
      }
      node_exporter {
        enabled_in_broker = true
      }
    }
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = var.broker_log_group_name
      }
    }
  }

  tags = merge(local.common_tags, { Name = local.cluster_name })

  lifecycle {
    # Storage can be grown but never shrunk. Ignoring it prevents a plan that
    # proposes an impossible change after autoscaling has expanded a volume.
    ignore_changes = [broker_node_group_info[0].storage_info[0].ebs_storage_info[0].volume_size]
  }
}

# -----------------------------------------------------------------------------
# Storage autoscaling.
#
# A broker that runs out of disk stops accepting writes. Expanding storage takes
# long enough that noticing manually means an outage has already happened.
# -----------------------------------------------------------------------------
resource "aws_appautoscaling_target" "broker_storage" {
  count = var.storage_autoscaling_enabled ? 1 : 0

  service_namespace  = "kafka"
  resource_id        = aws_msk_cluster.this.arn
  scalable_dimension = "kafka:broker-storage:VolumeSize"
  min_capacity       = var.broker_ebs_volume_size
  max_capacity       = var.broker_ebs_max_volume_size
}

resource "aws_appautoscaling_policy" "broker_storage" {
  count = var.storage_autoscaling_enabled ? 1 : 0

  name               = "${local.cluster_name}-storage-autoscaling"
  service_namespace  = aws_appautoscaling_target.broker_storage[0].service_namespace
  resource_id        = aws_appautoscaling_target.broker_storage[0].resource_id
  scalable_dimension = aws_appautoscaling_target.broker_storage[0].scalable_dimension
  policy_type        = "TargetTrackingScaling"

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "KafkaBrokerStorageUtilization"
    }
    # Expands at 70% rather than 90%: an EBS volume expansion is not
    # instantaneous, and starting at 90% can still lose the race.
    target_value = var.storage_autoscaling_target_percent
  }
}
