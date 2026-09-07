#!/usr/bin/env bash
# =============================================================================
# Formats, validates and scans the Terraform.
#
# NEVER CONTACTS AWS AND NEVER CREATES ANYTHING.
#
# `terraform init -backend=false` is what makes that true: it downloads the
# providers so the configuration can be type-checked, but configures no backend,
# so it needs no credentials, reads no state and writes nothing.
#
# `terraform plan` is deliberately NOT run. A plan requires credentials and
# reads live state, and this repository's whole premise is that nothing has been
# deployed. Planning happens in a separate, reviewed, OIDC-authenticated
# workflow that this script does not invoke.
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}/infrastructure/terraform"

echo "==> terraform fmt"
if ! terraform fmt -check -recursive; then
    echo "Formatting problems above. Run 'terraform fmt -recursive' to fix them."
    exit 1
fi
echo "    all files formatted"

echo ""
echo "==> terraform validate"
# Every environment. A module is only type-checked in the context of a caller
# that supplies its variables, so validating the modules alone proves less.
for env_dir in environments/*/; do
    env_name="$(basename "${env_dir}")"
    echo "    ${env_name}"

    # -backend=false: no S3, no DynamoDB, no credentials, nothing created.
    terraform -chdir="${env_dir}" init -backend=false -input=false -no-color > /dev/null
    terraform -chdir="${env_dir}" validate -no-color
done

# A module that no environment calls is never type-checked by the loop above,
# so a syntax error in it would go unnoticed until the day somebody wires it in.
# Validating it standalone proves less -- variables have their defaults rather
# than a caller's values -- but it proves the file parses, which is the failure
# actually being guarded against here.
echo ""
echo "==> terraform validate (modules with no caller)"
uncalled=0
for module_dir in modules/*/; do
    module_name="$(basename "${module_dir}")"

    if grep -rq "modules/${module_name}\"" environments/; then
        continue
    fi

    uncalled=1
    echo "    ${module_name} (not referenced by any environment)"
    terraform -chdir="${module_dir}" init -backend=false -input=false -no-color > /dev/null
    terraform -chdir="${module_dir}" validate -no-color
done
if [[ "${uncalled}" -eq 0 ]]; then
    echo "    none; every module has a caller"
fi

echo ""
echo "==> Checking for values that must never be committed"

# A real AWS account id is twelve digits. The placeholder is twelve zeroes.
if grep -rnE '[0-9]{12}' --include='*.tf' --include='*.tfvars' . \
        | grep -v '000000000000' \
        | grep -vE '^\S+: *#' > /dev/null 2>&1; then
    echo "Something that looks like a real AWS account id is present:"
    grep -rnE '[0-9]{12}' --include='*.tf' --include='*.tfvars' . | grep -v '000000000000' || true
    exit 1
fi
echo "    no real AWS account identifiers"

if find . -name '*.tfstate' -o -name '*.tfstate.*' -o -name '*.tfplan' | grep -q .; then
    echo "State or plan files are present. Both can contain secrets and neither may be committed."
    exit 1
fi
echo "    no state or plan files"

if grep -rn 'backend "s3"' --include='*.tf' . > /dev/null 2>&1; then
    echo "A backend block is configured. It would publish the state bucket's name"
    echo "and the account it lives in. See docs/operations/terraform-state-bootstrap.md."
    exit 1
fi
echo "    no committed backend configuration"

echo ""
echo "==> tflint"
if command -v tflint >/dev/null 2>&1; then
    tflint --init > /dev/null 2>&1 || true
    tflint --recursive --format compact || {
        echo "tflint reported problems."
        exit 1
    }
    echo "    clean"
else
    echo "    tflint is not installed; SKIPPED (CI always runs it)."
fi

echo ""
echo "==> checkov"
if command -v checkov >/dev/null 2>&1; then
    checkov \
        --directory . \
        --quiet \
        --compact \
        --framework terraform \
        --config-file "${REPO_ROOT}/.checkov.yaml" || {
        echo ""
        echo "Checkov reported findings. Each must be either fixed or suppressed"
        echo "with a written justification -- never silenced without one."
        exit 1
    }
    echo "    clean"
else
    echo "    checkov is not installed; SKIPPED (CI always runs it)."
fi

echo ""
echo "Terraform formats, validates and scans."
echo "NOTHING WAS PLANNED OR APPLIED. No AWS resource was created."
