# =============================================================================
# Customer managed KMS keys.
#
# WHY NOT THE AWS-MANAGED KEYS
# ----------------------------
# aws/rds, aws/s3 and friends are free and require no configuration, which is
# exactly why they are tempting. What they cannot do:
#
#   * carry a key policy, so access cannot be restricted beyond IAM;
#   * be disabled, so there is no way to render data unreadable during an
#     incident;
#   * be shared across accounts;
#   * be rotated on a schedule you control;
#   * appear in CloudTrail with per-grant detail.
#
# The last two matter most. "Prove that only these principals could decrypt this
# data" is a question an auditor asks, and only a customer managed key can
# answer it.
#
# COST: $1/month per key plus request charges. Negligible next to what it
# protects.
#
# NOTHING HERE HAS BEEN APPLIED.
# =============================================================================

data "aws_caller_identity" "current" {}

locals {
  common_tags = merge(var.tags, { Module = "kms" })
}

# -----------------------------------------------------------------------------
# Key policy.
#
# The root-account statement looks alarming and is mandatory: without it the key
# becomes unmanageable, because IAM policies cannot grant access to a key whose
# own policy does not delegate to the account. AWS will refuse to create a key
# that could be permanently orphaned.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "key" {
  # These three findings are all the same finding, and all three are a category
  # error on checkov's part: the checks are written for an IAM policy attached
  # to a principal, where "Resource": "*" means "every resource in the account".
  # This is a KEY POLICY. In a key policy "*" means "the key this policy is
  # attached to" -- there is no other resource it could mean, and AWS rejects a
  # key policy that names the key by ARN, because the ARN is not known until the
  # key exists.
  #
  # The root statement is likewise mandatory rather than lax. Without it the key
  # is orphaned: IAM policies cannot grant access to a key whose own policy does
  # not delegate to the account, and AWS refuses to create a key that could
  # become permanently unusable. The real control is that only the second
  # statement grants day-to-day use, and it is bound by kms:ViaService.
  #
  # checkov:skip=CKV_AWS_109:Key policy, not an identity policy. "*" is this key; the root delegation is required for the key to be manageable at all.
  # checkov:skip=CKV_AWS_111:As above. Write access is constrained by the ViaService condition on the statement that actually grants use.
  # checkov:skip=CKV_AWS_356:As above. A key policy cannot reference the key's own ARN, because it is set at creation.
  statement {
    sid    = "EnableIAMPoliciesForAccount"
    effect = "Allow"

    principals {
      type        = "AWS"
      identifiers = ["arn:${var.aws_partition}:iam::${data.aws_caller_identity.current.account_id}:root"]
    }

    actions   = ["kms:*"]
    resources = ["*"]
  }

  # Services that need to encrypt on the account's behalf. Scoped with
  # ViaService so the grant only applies when the request genuinely originates
  # from that service -- without it, any principal that can call KMS could use
  # the key directly.
  dynamic "statement" {
    for_each = length(var.service_principals) > 0 ? [1] : []

    content {
      sid    = "AllowAWSServiceUse"
      effect = "Allow"

      principals {
        type        = "Service"
        identifiers = var.service_principals
      }

      actions = [
        "kms:Encrypt",
        "kms:Decrypt",
        "kms:ReEncrypt*",
        "kms:GenerateDataKey*",
        "kms:DescribeKey",
        "kms:CreateGrant",
      ]

      resources = ["*"]

      condition {
        test     = "StringEquals"
        variable = "kms:ViaService"
        values   = var.via_services
      }
    }
  }
}

resource "aws_kms_key" "this" {
  description = var.description

  # Symmetric encryption. Asymmetric keys exist for signing and for handing a
  # public key to a party you do not trust with the private one; neither applies
  # to encrypting data at rest.
  key_usage                = "ENCRYPT_DECRYPT"
  customer_master_key_spec = "SYMMETRIC_DEFAULT"

  # Annual rotation. AWS keeps the old key material, so previously encrypted
  # data stays readable and rotation is genuinely free of risk -- there is no
  # reason to leave it off.
  enable_key_rotation = true

  # A deletion window, not immediate deletion. Deleting a KMS key destroys
  # everything encrypted under it, irreversibly. The window is the only chance
  # to notice.
  deletion_window_in_days = var.deletion_window_in_days

  # Multi-Region only where a DR replica in another region must decrypt the same
  # data. Multi-Region keys are otherwise a needless widening of blast radius.
  multi_region = var.multi_region

  policy = data.aws_iam_policy_document.key.json

  tags = merge(local.common_tags, { Name = var.alias_name })
}

# An alias, so callers reference a stable name rather than a key id. Rotating to
# a new key later becomes an alias change instead of a search through every
# resource that mentioned the old one.
resource "aws_kms_alias" "this" {
  name          = "alias/${var.alias_name}"
  target_key_id = aws_kms_key.this.key_id
}
