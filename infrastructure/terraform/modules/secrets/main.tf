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

  # One database credential per service. The chart mounts
  # "<environment>/los/<service>/datasource-password" into each pod, and the iam
  # module grants each service its own entry and no other.
  database_secrets = {
    for service in var.services :
    service => "${var.environment}/los/${service}/datasource-password"
  }
}

# -----------------------------------------------------------------------------
# Per-service database credentials.
# -----------------------------------------------------------------------------
resource "aws_secretsmanager_secret" "database" {
  for_each = local.database_secrets

  name        = each.value
  description = "Database password for ${each.key} in ${var.environment}. Set out of band; never through Terraform."

  kms_key_id = var.kms_key_arn

  # A window, not immediate deletion. Deleting a secret the platform is using
  # takes it down, and the window is the only chance to notice. Zero would make
  # `terraform destroy` irreversible for the one resource whose loss is hardest
  # to recover from.
  recovery_window_in_days = var.recovery_window_in_days

  tags = merge(local.common_tags, { Service = each.key })
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
# Configured only where a rotation function exists. AWS Secrets Manager rotation
# requires a Lambda that knows how to change the credential at its source, and
# writing one is a real piece of work rather than a flag -- so this is off by
# default and the module says so, instead of implying a rotation that never
# happens.
#
# The RDS MASTER password is a separate matter and is already handled: the
# rds-postgresql module sets manage_master_user_password, so AWS generates,
# stores and rotates it, and it never passes through Terraform at all.
# -----------------------------------------------------------------------------
resource "aws_secretsmanager_secret_rotation" "database" {
  for_each = var.rotation_lambda_arn == null ? {} : local.database_secrets

  secret_id           = aws_secretsmanager_secret.database[each.key].id
  rotation_lambda_arn = var.rotation_lambda_arn

  rotation_rules {
    automatically_after_days = var.rotation_days
  }
}

# -----------------------------------------------------------------------------
# Resource policies.
#
# Defence in depth. The iam module already grants each service its own secret and
# no other; this says the same thing from the resource's side, so a mistakenly
# broad identity policy elsewhere does not open the secret.
#
# The two controls have to BOTH allow: an identity policy grant that the resource
# policy does not permit is denied, and the reverse likewise.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "database" {
  for_each = var.enforce_resource_policies ? local.database_secrets : {}

  statement {
    sid    = "OnlyTheOwningServiceAndAdministrators"
    effect = "Deny"

    principals {
      type        = "AWS"
      identifiers = ["*"]
    }

    actions   = ["secretsmanager:GetSecretValue"]
    resources = ["*"]

    # Everyone EXCEPT the owning service's role and the named administrators.
    # A deny with a NotPrincipal-style condition rather than an allow, because an
    # allow here would have to enumerate every legitimate caller including the
    # ones AWS uses internally.
    condition {
      test     = "ArnNotEquals"
      variable = "aws:PrincipalArn"
      values = concat(
        [lookup(var.service_role_arns, each.key, "arn:${var.aws_partition}:iam::${var.account_id}:role/nonexistent")],
        var.secret_administrator_role_arns,
      )
    }
  }
}

resource "aws_secretsmanager_secret_policy" "database" {
  for_each = var.enforce_resource_policies ? local.database_secrets : {}

  secret_arn = aws_secretsmanager_secret.database[each.key].arn
  policy     = data.aws_iam_policy_document.database[each.key].json

  # Refuses a policy that would lock the secret away from everyone, which is
  # otherwise an easy mistake to make and a hard one to undo.
  block_public_policy = true
}

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
        [lookup(var.service_role_arns, "application-service", "arn:${var.aws_partition}:iam::${var.account_id}:role/nonexistent")],
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
