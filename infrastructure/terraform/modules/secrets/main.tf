# =============================================================================
# Secrets Manager entries.
#
# THIS MODULE CREATES CONTAINERS, NEVER VALUES, AND THAT IS THE WHOLE DESIGN.
#
# Terraform state holds every attribute of every resource it manages. A secret
# whose value Terraform knows is a secret in the state file -- in the state
# bucket, in every plan output, in the CI job that produced it, and in whatever
# any of those were copied to. Generating one with `random_password` is worse,
# not better: it looks careful and puts a real credential in state.
#
# So this module declares NO aws_secretsmanager_secret_version resource at all.
# That is the whole mechanism, and it is stronger than an `ignore_changes` on a
# managed version would be: there is no value attribute for Terraform to read,
# diff, or store. The value is placed once, out of band, by a human or a rotation
# function, and Terraform never learns it.
#
# WHAT THIS MEANS IN PRACTICE: after a first apply the secrets exist and are
# empty, and every pod that mounts one fails to start. That is the correct
# failure. A placeholder value that let the platform start would be a working
# system with a known credential in it.
#
# NOTHING HERE HAS BEEN APPLIED. No secret exists and no value appears anywhere
# in this repository.
# =============================================================================

locals {
  common_tags = merge(var.tags, { Module = "secrets" })
}

# -----------------------------------------------------------------------------
# The applicant reference pepper.
#
# NOT AN ORDINARY CREDENTIAL, and it must not be treated as one.
#
# It is an input to the HMAC that produces every applicant reference, and those
# references are stored in every table and every audit record. Rotating it
# changes every reference: cross-context joins break, and the audit trail CANNOT
# be recomputed, because it is append-only and hash-chained.
#
# So it has no rotation schedule, deliberately. Changing it is a data migration
# with a versioned pepper, not an operational task. See
# docs/operations/runbooks/secret-rotation.md and ADR-0010.
# -----------------------------------------------------------------------------
resource "aws_secretsmanager_secret" "applicant_pepper" {
  # checkov:skip=CKV2_AWS_57:Automatic rotation would CORRUPT DATA rather than protect it. This value is an input to the HMAC that produces every applicant reference, and those references are stored in every table and every audit record. Rotating it changes every reference: cross-context joins break, and the audit trail cannot be recomputed because it is append-only and hash-chained. Changing it is a versioned data migration -- see ADR-0010 and docs/operations/runbooks/secret-rotation.md -- and a schedule that did it unattended, monthly, is the opposite of a safeguard.

  name        = "${var.environment}/los/applicant-reference-pepper"
  description = "HMAC pepper for applicant references. Rotating this is a DATA MIGRATION, not a rotation. See ADR-0010."

  kms_key_id = var.kms_key_arn

  # Longer than the others. Losing this makes every stored reference
  # uninterpretable, and no backup of the database recovers it.
  recovery_window_in_days = var.pepper_recovery_window_in_days

  tags = merge(local.common_tags, {
    Sensitivity = "irreplaceable"
  })
}

# -----------------------------------------------------------------------------
# Rotation.
#
# There is nothing here to rotate on a schedule, and that is the point of the
# change that removed it. The services authenticate to PostgreSQL with IAM
# database authentication -- the pod's IAM role is the credential, and RDS issues
# a token valid for fifteen minutes -- so there is no database password to
# rotate, leak or log.
#
# The RDS MASTER password still exists and is already handled: the
# rds-postgresql module sets manage_master_user_password, so AWS generates,
# stores and rotates it, and it never passes through Terraform.
#
# The applicant pepper is deliberately never rotated on a schedule. Doing so
# would corrupt data rather than protect it. See ADR-0010.
# -----------------------------------------------------------------------------

# -----------------------------------------------------------------------------
# Resource policies.
#
# Defence in depth. The iam module already grants the pepper to the application
# service alone; this says the same thing from the resource's side, so a
# mistakenly broad identity policy elsewhere does not open it.
#
# The two controls have to BOTH allow: an identity policy grant that the resource
# policy does not permit is denied, and the reverse likewise.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "applicant_pepper" {
  count = var.enforce_resource_policies ? 1 : 0

  statement {
    sid    = "OnlyTheApplicationServiceAndAdministrators"
    effect = "Deny"

    principals {
      type        = "AWS"
      identifiers = ["*"]
    }

    actions   = ["secretsmanager:GetSecretValue"]
    resources = ["*"]

    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values = concat(
        [coalesce(var.application_service_role_arn, "arn:${var.aws_partition}:iam::${var.account_id}:role/nonexistent")],
        var.secret_administrator_role_arns,
      )
    }
  }
}

resource "aws_secretsmanager_secret_policy" "applicant_pepper" {
  count = var.enforce_resource_policies ? 1 : 0

  secret_arn          = aws_secretsmanager_secret.applicant_pepper.arn
  policy              = data.aws_iam_policy_document.applicant_pepper[0].json
  block_public_policy = true
}
