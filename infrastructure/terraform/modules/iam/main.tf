# =============================================================================
# Per-service AWS identities.
#
# THE MOST IMPORTANT AUTHORISATION BOUNDARY IN THE DEPLOYMENT. One role per
# service, and each role holds only what that service actually uses.
#
# A single shared role would give every pod the union of all four services'
# permissions: the workflow service, which touches no object storage at all,
# would be able to read every uploaded document, and a compromise of the least
# sensitive service would yield the most sensitive service's access.
#
# EKS POD IDENTITY, NOT IRSA. The chart's ServiceAccounts declare it and this
# module creates the associations, because a Kubernetes manifest that could grant
# itself an IAM role would defeat the separation entirely. Pod Identity also
# removes the OIDC trust relationship, so a role cannot be assumed from outside
# the cluster even if a trust policy is wrong.
#
# WHAT IS NOT HERE: the roles for cluster add-ons -- the AWS Load Balancer
# Controller, the cluster autoscaler, the EBS CSI driver, the Secrets Store CSI
# driver. Those are cluster infrastructure rather than this platform's services,
# they use IRSA against the OIDC provider the eks module creates, and they are
# recorded as missing rather than quietly folded in here.
#
# NOTHING HERE HAS BEEN APPLIED.
# =============================================================================

locals {
  common_tags = merge(var.tags, { Module = "iam" })

  # MSK IAM resource ARNs are derived from the cluster ARN by substituting the
  # resource type. Written out rather than wildcarded: `topic/*` would let any
  # service read every topic, which is exactly the boundary this module exists
  # to draw.
  msk_topic_prefix = replace(var.msk_cluster_arn, ":cluster/", ":topic/")
  msk_group_prefix = replace(var.msk_cluster_arn, ":cluster/", ":group/")

  # -------------------------------------------------------------------------
  # What each service actually does.
  #
  # Declared in one table so the whole authorisation model can be read at once.
  # Anything absent here is permission the service does not get.
  # -------------------------------------------------------------------------
  services = {
    "application-service" = {
      # Owns the application aggregate; publishes its events and consumes the
      # outcomes it needs to record a decision.
      produces_topics = ["los.application.events.v1"]
      consumes_topics = ["los.document.events.v1", "los.workflow.events.v1"]
      consumer_groups = [
        "application-service.document-events",
        "application-service.workflow-events",
      ]
      # The only service that derives the applicant pseudonym, so the only one
      # that may read the pepper. The chart scopes the mount the same way.
      reads_applicant_pepper = true
      s3_access              = null
    }

    "workflow-service" = {
      produces_topics        = ["los.workflow.events.v1"]
      consumes_topics        = ["los.application.events.v1"]
      consumer_groups        = ["workflow-service.application-events"]
      reads_applicant_pepper = false
      # No object storage at all. The workflow context holds no documents and
      # never sees one.
      s3_access = null
    }

    "document-service" = {
      produces_topics        = ["los.document.events.v1"]
      consumes_topics        = []
      consumer_groups        = []
      reads_applicant_pepper = false
      s3_access = {
        bucket_arn  = var.documents_bucket_arn
        kms_key_arn = var.documents_kms_key_arn
        append_only = false
      }
    }

    "audit-service" = {
      produces_topics = []
      # Consumes everything: the audit trail is the record of the whole
      # platform, which is the one place a broad read grant is the point rather
      # than a smell.
      consumes_topics = [
        "los.application.events.v1",
        "los.document.events.v1",
        "los.workflow.events.v1",
      ]
      consumer_groups        = ["audit-service.all-events"]
      reads_applicant_pepper = false
      s3_access = {
        bucket_arn  = var.audit_bucket_arn
        kms_key_arn = var.audit_kms_key_arn
        # THE IMPORTANT ONE. See the policy below.
        append_only = true
      }
    }
  }
}

# -----------------------------------------------------------------------------
# Trust policy.
#
# Identical for every service: the EKS Pod Identity agent assumes the role on a
# pod's behalf, and the association below is what binds a role to one service
# account in one namespace.
#
# sts:TagSession is required as well as sts:AssumeRole -- Pod Identity tags the
# session with the cluster, namespace and service account, which is what makes
# the CloudTrail record say WHICH pod did something rather than just which role.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "pod_identity_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole", "sts:TagSession"]

    principals {
      type        = "Service"
      identifiers = ["pods.eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "service" {
  for_each = local.services

  # Matches the ARN the chart's ServiceAccount annotation builds:
  # arn:aws:iam::<account>:role/<environment>-<service>
  name               = "${var.environment}-${each.key}"
  description        = "Least-privilege identity for ${each.key}"
  assume_role_policy = data.aws_iam_policy_document.pod_identity_assume_role.json

  # A ceiling on any session assumed with this role, whatever a caller asks for.
  max_session_duration = 3600

  tags = merge(local.common_tags, { Service = each.key })
}

# -----------------------------------------------------------------------------
# Binding a role to a pod.
#
# Created by Terraform rather than by the chart, deliberately. If a Kubernetes
# manifest could establish this association, anyone who could deploy a manifest
# could grant themselves any role in the account.
# -----------------------------------------------------------------------------
resource "aws_eks_pod_identity_association" "service" {
  for_each = local.services

  cluster_name = var.eks_cluster_name
  namespace    = var.kubernetes_namespace
  # The chart names its service accounts "<release>-<service>".
  service_account = "${var.helm_release_name}-${each.key}"
  role_arn        = aws_iam_role.service[each.key].arn

  tags = merge(local.common_tags, { Service = each.key })
}

# =============================================================================
# Policies
# =============================================================================

# -----------------------------------------------------------------------------
# What every service needs: its database, its topics, its secrets, its metrics.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "service" {
  for_each = local.services

  # -- The database ---------------------------------------------------------
  #
  # Scoped to ONE database user. `dbuser:*/*` would let any service connect as
  # any role, including the migration role that can drop tables.
  statement {
    sid       = "ConnectToOwnDatabaseUser"
    effect    = "Allow"
    actions   = ["rds-db:connect"]
    resources = ["arn:${var.aws_partition}:rds-db:${var.region}:${var.account_id}:dbuser:${var.db_resource_id}/${replace(each.key, "-service", "")}_runtime"]
  }

  # -- Kafka ----------------------------------------------------------------
  statement {
    sid    = "ConnectToCluster"
    effect = "Allow"
    actions = [
      "kafka-cluster:Connect",
      "kafka-cluster:DescribeCluster",
    ]
    resources = [var.msk_cluster_arn]
  }

  dynamic "statement" {
    for_each = length(each.value.produces_topics) > 0 ? [1] : []

    content {
      sid    = "ProduceToOwnTopics"
      effect = "Allow"
      actions = [
        "kafka-cluster:WriteData",
        "kafka-cluster:DescribeTopic",
      ]
      resources = [for topic in each.value.produces_topics : "${local.msk_topic_prefix}/${topic}"]
    }
  }

  dynamic "statement" {
    for_each = length(each.value.consumes_topics) > 0 ? [1] : []

    content {
      sid    = "ConsumeSubscribedTopics"
      effect = "Allow"
      actions = [
        "kafka-cluster:ReadData",
        "kafka-cluster:DescribeTopic",
      ]
      resources = [for topic in each.value.consumes_topics : "${local.msk_topic_prefix}/${topic}"]
    }
  }

  dynamic "statement" {
    for_each = length(each.value.consumer_groups) > 0 ? [1] : []

    content {
      sid    = "ManageOwnConsumerGroups"
      effect = "Allow"
      actions = [
        "kafka-cluster:AlterGroup",
        "kafka-cluster:DescribeGroup",
      ]
      # Named groups, not a wildcard. A service that can alter any group can
      # reset another service's offsets, which silently replays or skips events.
      resources = [for group in each.value.consumer_groups : "${local.msk_group_prefix}/${group}"]
    }
  }

  # Every service writes to the dead-letter topic: a consumer that cannot
  # dead-letter a poisoned message has to either drop it or block its partition.
  statement {
    sid    = "WriteToDeadLetterTopic"
    effect = "Allow"
    actions = [
      "kafka-cluster:WriteData",
      "kafka-cluster:DescribeTopic",
    ]
    resources = ["${local.msk_topic_prefix}/los.dlq.v1"]
  }

  # NOTE what is absent: kafka-cluster:CreateTopic and :DeleteTopic. A service
  # that can create a topic can create the wrong one, with the wrong partition
  # count, and nothing notices until throughput matters. Topics are created by a
  # deliberate administrative action.

  # -- Secrets ---------------------------------------------------------------
  #
  # Retrieved by the Secrets Store CSI driver using THIS pod's identity, so the
  # grant belongs on the service's own role rather than the driver's.
  #
  # The trailing "-*" is not laziness: Secrets Manager appends six random
  # characters to every secret ARN, so the exact ARN is not knowable from the
  # name alone.
  statement {
    sid       = "ReadOwnDatabaseSecret"
    effect    = "Allow"
    actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
    resources = ["arn:${var.aws_partition}:secretsmanager:${var.region}:${var.account_id}:secret:${var.environment}/los/${each.key}/datasource-password-*"]
  }

  dynamic "statement" {
    for_each = each.value.reads_applicant_pepper ? [1] : []

    content {
      sid       = "ReadApplicantPepper"
      effect    = "Allow"
      actions   = ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"]
      resources = ["arn:${var.aws_partition}:secretsmanager:${var.region}:${var.account_id}:secret:${var.environment}/los/applicant-reference-pepper-*"]
    }
  }

  # Decrypting those secrets, and only in the course of retrieving them.
  statement {
    sid       = "DecryptSecrets"
    effect    = "Allow"
    actions   = ["kms:Decrypt"]
    resources = [var.secrets_kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${var.region}.amazonaws.com"]
    }
  }

  # -- Metrics ---------------------------------------------------------------
  #
  # PutMetricData cannot be scoped by resource -- the API has none -- so it is
  # scoped by namespace condition instead. Without the condition a compromised
  # service could write into any namespace, including the ones the alarms read,
  # and fabricate a healthy platform.
  statement {
    sid       = "PublishOwnMetrics"
    effect    = "Allow"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = [var.metric_namespace]
    }
  }

  # -- Object storage, for the two services that use it ----------------------
  dynamic "statement" {
    for_each = each.value.s3_access == null ? [] : [each.value.s3_access]

    content {
      sid    = "ListOwnBucket"
      effect = "Allow"
      # Listing is separate from reading, and is granted on the BUCKET rather
      # than its objects. Without it the SDK's existence checks fail in ways
      # that read as a missing object rather than a missing permission.
      actions   = ["s3:ListBucket"]
      resources = [statement.value.bucket_arn]
    }
  }

  # The document service: quarantine and accepted prefixes only, and delete only
  # from quarantine, because promoting a scanned object copies then removes the
  # original.
  dynamic "statement" {
    for_each = each.value.s3_access != null && !each.value.s3_access.append_only ? [each.value.s3_access] : []

    content {
      sid    = "ReadWriteDocuments"
      effect = "Allow"
      actions = [
        "s3:GetObject",
        "s3:PutObject",
        "s3:DeleteObject",
      ]
      resources = [
        "${statement.value.bucket_arn}/quarantine/*",
        "${statement.value.bucket_arn}/accepted/*",
      ]
    }
  }

  # ---------------------------------------------------------------------------
  # The audit service: PUT ONLY.
  #
  # This mirrors the database grant exactly. The audit runtime role holds SELECT
  # and INSERT on audit.audit_record and nothing else, verified against a real
  # PostgreSQL; the same service's object storage grant holds PutObject and
  # nothing else.
  #
  # No GetObject either. The audit service writes the durable copy and never
  # reads it back: reading is an investigator's action, through a role a human
  # assumes.
  # ---------------------------------------------------------------------------
  dynamic "statement" {
    for_each = each.value.s3_access != null && each.value.s3_access.append_only ? [each.value.s3_access] : []

    content {
      sid       = "AppendAuditRecordsOnly"
      effect    = "Allow"
      actions   = ["s3:PutObject"]
      resources = ["${statement.value.bucket_arn}/*"]
    }
  }

  # An EXPLICIT DENY on top of the narrow grant above.
  #
  # Belt and braces, and it earns its place: an explicit deny cannot be undone by
  # a later, broader policy attached to the same role. Somebody attaching
  # AmazonS3FullAccess to fix an unrelated problem does not silently make the
  # audit trail deletable.
  dynamic "statement" {
    for_each = each.value.s3_access != null && each.value.s3_access.append_only ? [each.value.s3_access] : []

    content {
      sid    = "NeverDeleteOrWeakenTheAuditTrail"
      effect = "Deny"
      actions = [
        "s3:DeleteObject",
        "s3:DeleteObjectVersion",
        "s3:PutLifecycleConfiguration",
        "s3:PutBucketVersioning",
        "s3:PutObjectRetention",
        "s3:PutObjectLegalHold",
        "s3:BypassGovernanceRetention",
      ]
      resources = [
        statement.value.bucket_arn,
        "${statement.value.bucket_arn}/*",
      ]
    }
  }

  # Encrypting and decrypting whatever the service stores, bound to S3 so the key
  # cannot be used directly.
  dynamic "statement" {
    for_each = each.value.s3_access == null ? [] : [each.value.s3_access]

    content {
      sid    = "UseOwnDataKey"
      effect = "Allow"
      actions = [
        "kms:Decrypt",
        "kms:GenerateDataKey",
      ]
      resources = [statement.value.kms_key_arn]

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = ["s3.${var.region}.amazonaws.com"]
      }
    }
  }
}

resource "aws_iam_policy" "service" {
  for_each = local.services

  name        = "${var.environment}-${each.key}"
  description = "Least-privilege policy for ${each.key}"
  policy      = data.aws_iam_policy_document.service[each.key].json

  tags = merge(local.common_tags, { Service = each.key })
}

resource "aws_iam_role_policy_attachment" "service" {
  for_each = local.services

  role       = aws_iam_role.service[each.key].name
  policy_arn = aws_iam_policy.service[each.key].arn
}
