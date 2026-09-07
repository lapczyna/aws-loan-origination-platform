# =============================================================================
# The documents bucket.
#
# Holds customer-uploaded files, which makes it the most sensitive storage on
# the platform after the database. Every control below exists because of a
# specific, well-documented way S3 buckets leak.
#
# NOTHING HERE HAS BEEN APPLIED.
# =============================================================================

locals {
  bucket_name = "${var.environment}-${var.name}"
  common_tags = merge(var.tags, { Module = "s3-documents" })
}

resource "aws_s3_bucket" "this" {
  bucket = local.bucket_name

  # Refuses `terraform destroy` while the bucket contains objects. Customer
  # documents are not something to lose to a mistyped workspace.
  force_destroy = var.force_destroy

  tags = merge(local.common_tags, { Name = local.bucket_name })
}

# -----------------------------------------------------------------------------
# Block ALL public access.
#
# The single most important S3 setting. Applied at the bucket level as well as
# the account level, because an account-level setting can be relaxed by someone
# who does not realise which buckets it was protecting.
#
# All four flags, not merely the first two:
#   BlockPublicAcls        rejects new public ACLs
#   IgnorePublicAcls       ignores public ACLs that already exist
#   BlockPublicPolicy      rejects a bucket policy that would grant public access
#   RestrictPublicBuckets  ignores public grants in an existing policy
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# -----------------------------------------------------------------------------
# Disable ACLs entirely.
#
# BucketOwnerEnforced makes ACLs inoperative, so access is governed by policies
# alone. ACLs are the legacy mechanism behind most public-bucket incidents:
# they can be set per object, they are easy to set by accident, and they are
# invisible unless someone thinks to look at each object.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_ownership_controls" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# -----------------------------------------------------------------------------
# Encryption with a customer managed key.
#
# bucket_key_enabled is not a security setting but a cost one, and a large
# one: without it every object operation is a separate KMS API call, billed per
# request. A bucket key caches a data key and cuts KMS charges by orders of
# magnitude on a busy bucket.
# -----------------------------------------------------------------------------
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

# -----------------------------------------------------------------------------
# Versioning.
#
# An overwritten or deleted document is recoverable. It also means a delete
# leaves a delete marker rather than destroying data, which is what makes the
# lifecycle rules below safe to write.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_versioning" "this" {
  bucket = aws_s3_bucket.this.id

  versioning_configuration {
    status = "Enabled"
  }
}

# -----------------------------------------------------------------------------
# Lifecycle.
#
# Three rules, each closing a specific gap.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  # Objects uploaded to quarantine and never completed. Without this they
  # accumulate forever, unscanned and unreferenced, and are billed for as
  # long as they exist.
  rule {
    id     = "expire-abandoned-quarantine-objects"
    status = "Enabled"

    filter {
      prefix = "quarantine/"
    }

    expiration {
      days = var.quarantine_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 1
    }
  }

  # Multipart uploads that were started and abandoned. Their parts are invisible
  # in the console and billed indefinitely -- a classic source of storage cost
  # nobody can account for.
  rule {
    id     = "abort-incomplete-multipart-uploads"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }

  # Accepted documents age into cheaper storage. Supporting documents are read
  # heavily during assessment and almost never afterwards, so this is close to
  # free savings.
  rule {
    id     = "transition-accepted-documents"
    status = "Enabled"

    filter {
      prefix = "accepted/"
    }

    transition {
      days          = var.accepted_transition_days
      storage_class = "STANDARD_IA"
    }

    # Old versions of a replaced document have no business value after a short
    # window.
    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_retention_days
    }
  }
}

# -----------------------------------------------------------------------------
# Bucket policy.
#
# Two deny statements. Deny rather than allow, because a Deny cannot be
# overridden by any IAM policy: it is the only way to state a rule that holds
# regardless of what someone grants elsewhere in the account.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "bucket" {
  # TLS only. Without this an object can be fetched over plain HTTP, and a
  # presigned URL -- which carries its own signature in the query string -- would
  # travel in clear text.
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

  # Every write must use the intended KMS key. Without this an uploader could
  # write objects encrypted under a different key -- or under SSE-S3 -- and the
  # bucket's encryption guarantee would quietly become optional.
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
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = data.aws_iam_policy_document.bucket.json

  # The public access block must exist first. Applying a policy before it leaves
  # a window in which a permissive policy would actually take effect.
  depends_on = [aws_s3_bucket_public_access_block.this]
}

# -----------------------------------------------------------------------------
# Access logging.
#
# Server access logs record who read what. They go to a SEPARATE bucket: logging
# a bucket into itself creates an infinite loop, because each log write is
# itself an access that must be logged.
#
# Note the logs record the object key and the query string. Keys on this
# platform are built from opaque identifiers precisely so that these logs cannot
# leak an applicant's identity.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_logging" "this" {
  count = var.access_log_bucket_name == null ? 0 : 1

  bucket = aws_s3_bucket.this.id

  target_bucket = var.access_log_bucket_name
  target_prefix = "s3-access-logs/${local.bucket_name}/"
}

# -----------------------------------------------------------------------------
# CORS.
#
# Needed because the browser uploads directly to S3 through a presigned URL, so
# the PUT is cross-origin. Deliberately narrow: only PUT, only the configured
# origins, and no wildcard. A wildcard here would let any website in the world
# script an upload using a URL it had somehow obtained.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_cors_configuration" "this" {
  count = length(var.cors_allowed_origins) > 0 ? 1 : 0

  bucket = aws_s3_bucket.this.id

  cors_rule {
    allowed_methods = ["PUT"]
    allowed_origins = var.cors_allowed_origins
    allowed_headers = ["Content-Type", "Content-Length", "x-amz-checksum-sha256"]
    expose_headers  = ["ETag", "x-amz-checksum-sha256"]
    max_age_seconds = 3000
  }
}
