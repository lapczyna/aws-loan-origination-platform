# =============================================================================
# The audit bucket.
#
# OBJECT LOCK IS THE POINT OF THIS MODULE
# ---------------------------------------
# The database copy of the audit trail is the working one. This bucket holds the
# copy that survives the database -- and, crucially, survives someone who has
# access to the database.
#
# With Object Lock in COMPLIANCE mode an object cannot be deleted or overwritten
# by ANYONE for the retention period: not the account root user, not AWS support.
# That is a strong and genuinely irreversible commitment, which is why GOVERNANCE
# is the default. Governance can be bypassed by a principal holding
# s3:BypassGovernanceRetention, so it protects against accident rather than
# against intent.
#
# Object Lock CANNOT be enabled on an existing bucket. It is set at creation or
# never, making it one of the few decisions here that cannot be corrected later.
#
# NOTHING HERE HAS BEEN APPLIED.
# =============================================================================

locals {
  bucket_name = "${var.environment}-${var.name}"
  common_tags = merge(var.tags, { Module = "s3-audit" })
}

resource "aws_s3_bucket" "this" {
  bucket = local.bucket_name

  # Set at creation or never.
  object_lock_enabled = var.object_lock_enabled

  # Never true for an audit bucket.
  force_destroy = false

  tags = merge(local.common_tags, { Name = local.bucket_name })
}

resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Versioning is a PREREQUISITE for Object Lock, not merely a good idea: the lock
# applies to object versions, so a bucket without versioning cannot have one.
resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = var.kms_key_arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_object_lock_configuration" "this" {
  count = var.object_lock_enabled ? 1 : 0

  bucket = aws_s3_bucket.this.id

  rule {
    default_retention {
      mode = var.object_lock_mode
      days = var.retention_days
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}

# -----------------------------------------------------------------------------
# Bucket policy: TLS only, correct key only, and no deletion.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "bucket" {
  statement {
    sid    = "DenyUnencryptedTransport"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = ["s3:*"]

    resources = [
      aws_s3_bucket.this.arn,
      "${aws_s3_bucket.this.arn}/*",
    ]

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }

  statement {
    sid    = "DenyIncorrectEncryptionKey"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.this.arn}/*"]

    condition {
      test     = "StringNotEquals"
      variable = "s3:x-amz-server-side-encryption-aws-kms-key-id"
      values   = [var.kms_key_arn]
    }
  }

  # An explicit deny on deletion, in addition to Object Lock. Belt and braces:
  # a lock expires when its retention period does; this does not.
  statement {
    sid    = "DenyObjectDeletion"
    effect = "Deny"

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    actions = [
      "s3:DeleteObject",
      "s3:DeleteObjectVersion",
      "s3:PutLifecycleConfiguration",
    ]

    resources = [
      aws_s3_bucket.this.arn,
      "${aws_s3_bucket.this.arn}/*",
    ]

    # A named audit-administrator role stays exempt, so a legitimate retention
    # change remains possible through a reviewed path rather than not at all.
    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values   = var.audit_administrator_role_arns
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = data.aws_iam_policy_document.bucket.json

  depends_on = [aws_s3_bucket_public_access_block.this]
}

# -----------------------------------------------------------------------------
# Access logging.
#
# "Who read the audit trail" is a question that gets asked during an
# investigation, and it is the one question the audit trail cannot answer about
# itself. These logs answer it.
#
# The target is a separate bucket, necessarily: logging a bucket into itself is
# an infinite loop, because each log write is an access that must be logged.
# That is also why the terminal bucket in the chain has no logging of its own,
# recorded against CKV2_AWS_62 in .checkov.yaml.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_logging" "this" {
  count = var.access_log_bucket_name == null ? 0 : 1

  bucket = aws_s3_bucket.this.id

  target_bucket = var.access_log_bucket_name
  target_prefix = "s3-access-logs/${local.bucket_name}/"
}

# -----------------------------------------------------------------------------
# Lifecycle.
#
# THERE IS NO EXPIRATION RULE HERE, AND THERE MUST NOT BE. Object Lock would
# refuse the deletion anyway, but a lifecycle rule that tries and fails every
# day is a permanent stream of errors that trains people to ignore the bucket's
# alarms. The retention period is expressed once, by the Object Lock
# configuration above.
#
# What is left is what Object Lock does not cover:
#
#   * a transition to cheaper storage, which the lock permits, because moving an
#     object version is not deleting or overwriting it;
#   * aborting incomplete multipart uploads, whose parts are invisible in the
#     console and billed indefinitely.
#
# OPERATIONAL TRAP, STATED PLAINLY: the bucket policy denies
# s3:PutLifecycleConfiguration to every principal outside
# audit_administrator_role_arns. That deny applies to Terraform too. The role
# that applies this module must therefore be listed in
# audit_administrator_role_arns, or the FIRST apply succeeds -- the policy does
# not exist yet -- and every subsequent one fails with AccessDenied. The
# depends_on below fixes the ordering; it does not fix the permission.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    id     = "archive-old-audit-records"
    status = "Enabled"

    filter {}

    transition {
      days          = var.archive_transition_days
      storage_class = "GLACIER_IR"
    }

    # Non-current versions follow the same path. Versioning is mandatory for
    # Object Lock, so they exist whether or not anyone wanted them, and they are
    # billed at Standard rates until something moves them.
    noncurrent_version_transition {
      noncurrent_days = var.archive_transition_days
      storage_class   = "GLACIER_IR"
    }
  }

  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.this]
}
