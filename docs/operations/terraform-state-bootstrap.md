# Bootstrapping the Terraform state backend

> **This has never been run.** No state bucket exists, no lock table exists, and
> no Terraform state has ever been written for this project.

---

## Why there is no backend block in the repository

`environments/*/main.tf` deliberately contains no `backend "s3"` block, and a
check in `scripts/validate-terraform.sh` fails the build if one appears.

Two reasons:

1. **A backend block naming a real bucket publishes that bucket's name and the
   account it lives in.** That is reconnaissance handed to anyone who reads the
   repository, and this one is intended to become public.
2. **It lets `terraform init -backend=false` work.** With no backend configured,
   the configuration can be initialised, validated, linted and scanned with **no
   credentials and no network call to AWS**. That is what makes it possible to
   check this repository thoroughly without ever touching an account.

The backend is supplied at initialisation time instead:

```bash
terraform init \
  -backend-config="bucket=${STATE_BUCKET}" \
  -backend-config="key=dev/terraform.tfstate" \
  -backend-config="region=${AWS_REGION}" \
  -backend-config="dynamodb_table=${STATE_LOCK_TABLE}" \
  -backend-config="encrypt=true"
```

## The chicken and egg

The bucket that holds Terraform's state cannot itself be created by Terraform
using that state. Bootstrapping is a **one-time, manual, recorded** step.

Do it once per account, by hand, and write down who did it and when.

## What the state bucket must have

State is not configuration. It contains resource attributes and, for some
resources, **secret values**. Treat it as restricted data:

| Setting | Why |
|---|---|
| **Versioning enabled** | A corrupted or truncated state file is recoverable. Without it, a bad apply can orphan every resource in the account. |
| **KMS encryption** | It may contain secrets. |
| **Public access blocked, all four settings** | |
| **TLS-only bucket policy** | Deny `aws:SecureTransport = false`. |
| **Lifecycle: expire old versions after ~90 days** | Versions accumulate on every apply. |
| **Access logging** | Reading state is worth recording. |
| **No `force_destroy`** | Deleting state does not delete the resources; it orphans them. |

And a DynamoDB table for locking, with `LockID` as the partition key. Without it,
two concurrent applies corrupt the state — which is not a theoretical failure,
it is the normal outcome of two people running `apply` at once.

## Sketch

```bash
# ONE TIME, PER ACCOUNT, BY HAND. Substitute real values; nothing here is
# committed with a real bucket name or account id for the reasons above.
aws s3api create-bucket --bucket "${STATE_BUCKET}" \
    --region "${AWS_REGION}" \
    --create-bucket-configuration LocationConstraint="${AWS_REGION}"

aws s3api put-bucket-versioning --bucket "${STATE_BUCKET}" \
    --versioning-configuration Status=Enabled

aws s3api put-bucket-encryption --bucket "${STATE_BUCKET}" \
    --server-side-encryption-configuration '{
      "Rules": [{
        "ApplyServerSideEncryptionByDefault": {
          "SSEAlgorithm": "aws:kms",
          "KMSMasterKeyID": "'"${KMS_KEY_ARN}"'"
        },
        "BucketKeyEnabled": true
      }]
    }'

aws s3api put-public-access-block --bucket "${STATE_BUCKET}" \
    --public-access-block-configuration \
    "BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true"

aws dynamodb create-table --table-name "${STATE_LOCK_TABLE}" \
    --attribute-definitions AttributeName=LockID,AttributeType=S \
    --key-schema AttributeName=LockID,KeyType=HASH \
    --billing-mode PAY_PER_REQUEST \
    --region "${AWS_REGION}"
```

**These commands create AWS resources and cost money. They have not been run.**

## Who may read it

State is restricted. The plan role needs read access plus the lock table; only
the apply role needs write. A principal that can read state can read whatever
secrets it contains, so "read-only on the state bucket" is not a low-privilege
grant.

## If state is lost

Versioning is the answer, which is why it is not optional. Restore the previous
version. Without it, the options are `terraform import` for every resource — slow
and error-prone — or abandoning the resources.
