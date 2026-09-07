#!/usr/bin/env bash
# =============================================================================
# Creates the local S3 buckets and secrets, then exits.
#
# Runs inside LocalStack once it reports ready. Everything created here is
# local: no AWS account is contacted, and the credentials LocalStack accepts
# grant access to nothing outside this container.
#
# The bucket configuration mirrors what the Terraform s3-documents module
# provisions, so a policy or lifecycle mistake shows up locally rather than on
# first deployment. LocalStack does not enforce every one of these, but applying
# them keeps the two definitions honest with each other.
# =============================================================================

set -euo pipefail

REGION="${AWS_DEFAULT_REGION:-eu-west-1}"
DOCUMENTS_BUCKET="los-local-documents"
AUDIT_BUCKET="los-local-audit"

echo "Creating local S3 buckets..."

for bucket in "${DOCUMENTS_BUCKET}" "${AUDIT_BUCKET}"; do
    awslocal s3api create-bucket \
        --bucket "${bucket}" \
        --region "${REGION}" \
        --create-bucket-configuration "LocationConstraint=${REGION}" \
        >/dev/null 2>&1 || echo "  ${bucket} already exists"

    # Versioning: a document overwritten by accident is recoverable, and the
    # audit bucket's immutability depends on it.
    awslocal s3api put-bucket-versioning \
        --bucket "${bucket}" \
        --versioning-configuration Status=Enabled \
        >/dev/null

    # Block every form of public access. The single most important S3 setting.
    awslocal s3api put-public-access-block \
        --bucket "${bucket}" \
        --public-access-block-configuration \
            "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true" \
        >/dev/null

    # Bucket-owner-enforced disables ACLs entirely, so access is governed by
    # policies alone. ACLs are the legacy mechanism behind most public-bucket
    # incidents.
    awslocal s3api put-bucket-ownership-controls \
        --bucket "${bucket}" \
        --ownership-controls "Rules=[{ObjectOwnership=BucketOwnerEnforced}]" \
        >/dev/null 2>&1 || true

    echo "  ${bucket}: versioning on, public access blocked, ACLs disabled"
done

# Abandoned quarantine uploads expire. Without this, every upload that was
# requested and never completed accumulates forever, unscanned.
awslocal s3api put-bucket-lifecycle-configuration \
    --bucket "${DOCUMENTS_BUCKET}" \
    --lifecycle-configuration '{
        "Rules": [
            {
                "ID": "expire-abandoned-quarantine-objects",
                "Status": "Enabled",
                "Filter": {"Prefix": "quarantine/"},
                "Expiration": {"Days": 7}
            },
            {
                "ID": "abort-incomplete-multipart-uploads",
                "Status": "Enabled",
                "Filter": {"Prefix": ""},
                "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 1}
            }
        ]
    }' >/dev/null 2>&1 || echo "  (lifecycle rules not applied; LocalStack support varies by version)"

echo "Creating local secrets..."

# The applicant pseudonymisation pepper. Generated fresh, never committed.
# In AWS this secret is created by Terraform with a generated value and read
# through the Secrets Store CSI driver.
PEPPER="$(head -c 48 /dev/urandom | base64 | tr -d '\n=' | head -c 64)"
awslocal secretsmanager create-secret \
    --name "los/local/applicant-reference-pepper" \
    --secret-string "${PEPPER}" \
    >/dev/null 2>&1 || echo "  applicant-reference-pepper already exists"

echo "LocalStack initialisation complete."
