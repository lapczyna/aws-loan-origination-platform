# =============================================================================
# ECR repositories, one per service.
#
# IMMUTABLE TAGS
# --------------
# image_tag_mutability = "IMMUTABLE" is the most important setting here. With
# mutable tags, "0.1.0" can be re-pushed to point at different bytes, which
# means:
#   * a rollback to a tag can land on code that is not the code that tag
#     originally named;
#   * a vulnerability scan of a tag expires silently the moment it is re-pushed;
#   * an audit trail that records a tag records nothing verifiable.
# Immutable tags make a tag a permanent name for one set of bytes.
#
# NOTHING HERE HAS BEEN APPLIED, AND NO IMAGE HAS BEEN PUSHED.
# =============================================================================

locals {
  common_tags = merge(var.tags, { Module = "ecr" })
}

resource "aws_ecr_repository" "this" {
  for_each = toset(var.repository_names)

  name                 = "${var.environment}/${each.value}"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    # Scan on push, so a vulnerable image is known about before anyone deploys
    # it rather than at the next scheduled scan.
    scan_on_push = true
  }

  encryption_configuration {
    encryption_type = "KMS"
    kms_key         = var.kms_key_arn
  }

  # Refuses deletion while images remain. A deleted repository takes every
  # image with it, including the one currently running in production.
  force_delete = false

  tags = merge(local.common_tags, { Name = each.value })
}

# -----------------------------------------------------------------------------
# Lifecycle policy.
#
# Untagged images are build artefacts nobody will ever deploy; they are pure
# storage cost. Tagged images are capped rather than expired by age, because
# "the last N releases" is a rollback target and "anything from the last 90
# days" is not.
# -----------------------------------------------------------------------------
resource "aws_ecr_lifecycle_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Expire untagged images quickly: they are build artefacts, not releases."
        selection = {
          tagStatus   = "untagged"
          countType   = "sinceImagePushed"
          countUnit   = "days"
          countNumber = var.untagged_retention_days
        }
        action = { type = "expire" }
      },
      {
        rulePriority = 2
        description  = "Keep the most recent tagged images as rollback targets."
        selection = {
          tagStatus      = "tagged"
          tagPatternList = ["*"]
          countType      = "imageCountMoreThan"
          countNumber    = var.tagged_image_count
        }
        action = { type = "expire" }
      },
    ]
  })
}

# -----------------------------------------------------------------------------
# Repository policy.
#
# Pull is granted to the account's own principals only. A repository policy that
# grants "*" is how a private image becomes a public one.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "repository" {
  statement {
    sid    = "AllowPullFromThisAccount"
    effect = "Allow"

    principals {
      type        = "AWS"
      identifiers = ["arn:${var.aws_partition}:iam::${var.account_id}:root"]
    }

    actions = [
      "ecr:GetDownloadUrlForLayer",
      "ecr:BatchGetImage",
      "ecr:BatchCheckLayerAvailability",
    ]
  }
}

resource "aws_ecr_repository_policy" "this" {
  for_each = aws_ecr_repository.this

  repository = each.value.name
  policy     = data.aws_iam_policy_document.repository.json
}
