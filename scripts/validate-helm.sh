#!/usr/bin/env bash
# =============================================================================
# Lints, renders and schema-validates the Helm charts.
#
# NEVER CONNECTS TO A CLUSTER. `helm template` renders locally, and kubeconform
# validates the output against the Kubernetes OpenAPI schemas fetched from a
# public schema repository -- no kubeconfig, no API server, no credentials.
#
# The point of rendering EVERY environment is that a values file only used in
# production is a values file whose mistakes are only discovered in production.
# =============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${REPO_ROOT}"

CHART="deploy/helm/loan-origination-platform"
RENDER_DIR="$(mktemp -d)"
trap 'rm -rf "${RENDER_DIR}"' EXIT

# Matches the kubeVersion floor in Chart.yaml.
KUBE_VERSION="${KUBE_VERSION:-1.29.0}"

echo "==> helm lint"
helm lint "${CHART}"
for env_file in "${CHART}"/environments/values-*.yaml; do
    echo "    with $(basename "${env_file}")"
    helm lint "${CHART}" --values "${env_file}"
done

echo ""
echo "==> helm template"
# Default values, then each environment. A render is the only way to catch a
# template that is syntactically fine but produces invalid YAML.
helm template los "${CHART}" \
    --kube-version "${KUBE_VERSION}" \
    > "${RENDER_DIR}/default.yaml"
echo "    default values rendered"

for env_file in "${CHART}"/environments/values-*.yaml; do
    name="$(basename "${env_file}" .yaml)"
    helm template los "${CHART}" \
        --kube-version "${KUBE_VERSION}" \
        --values "${env_file}" \
        > "${RENDER_DIR}/${name}.yaml"
    echo "    ${name} rendered"
done

echo ""
echo "==> Checking the rendered manifests for anything that must not be there"

# A rendered chart is the last point at which a secret could be caught before it
# reaches a cluster. These are cheap checks against expensive mistakes.
if grep -rniE 'password: *[^$"'"'"'{ ]|secretKeyRef|kind: Secret$' "${RENDER_DIR}" >/dev/null 2>&1; then
    echo "A rendered manifest appears to contain a literal secret or a Kubernetes Secret."
    echo "Secrets are projected as files by the Secrets Store CSI driver and must never"
    echo "appear in a manifest."
    grep -rniE 'password: *[^$"'"'"'{ ]|secretKeyRef|kind: Secret$' "${RENDER_DIR}" || true
    exit 1
fi
echo "    no literal secrets and no Kubernetes Secret objects"

if grep -rn 'internet-facing' "${RENDER_DIR}" >/dev/null 2>&1; then
    echo "A rendered manifest requests an internet-facing load balancer, which would"
    echo "bypass WAF, the JWT authorizer, throttling and access logging."
    exit 1
fi
echo "    no internet-facing load balancer"

if grep -rnE 'arn:aws:[a-z0-9-]+:[a-z0-9-]*:[0-9]{12}:' "${RENDER_DIR}" \
        | grep -v '000000000000' >/dev/null 2>&1; then
    echo "A rendered manifest contains what looks like a real AWS account identifier."
    exit 1
fi
echo "    no real AWS account identifiers"

echo ""
echo "==> kubeconform"
if ! command -v kubeconform >/dev/null 2>&1; then
    echo "    kubeconform is not installed; schema validation SKIPPED."
    echo "    Install it from https://github.com/yannh/kubeconform/releases to enable it."
    echo "    (CI always runs it, so this is a local convenience only.)"
    exit 0
fi

for rendered in "${RENDER_DIR}"/*.yaml; do
    echo "    $(basename "${rendered}")"
    kubeconform \
        -strict \
        -summary \
        -kubernetes-version "${KUBE_VERSION}" \
        -schema-location default \
        -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' \
        "${rendered}"
done

echo ""
echo "Helm charts lint, render and validate. Nothing was applied to any cluster."
