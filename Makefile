# =============================================================================
# AWS Loan Origination Platform
#
# NOTHING IN THIS REPOSITORY HAS BEEN DEPLOYED TO AWS, AND NO TARGET IN THIS
# MAKEFILE CREATES, CHANGES OR DELETES AN AWS RESOURCE.
#
# Local Docker Compose is the default and only execution path. Targets that
# would cost money are absent by design: `terraform apply`, `helm install`,
# `kubectl apply` and image pushes are not automated here.
# =============================================================================

SHELL := /bin/bash
.DEFAULT_GOAL := help

MVN            := ./mvnw
COMPOSE_FILE   := deploy/compose/docker-compose.yml
COMPOSE        := docker compose -f $(COMPOSE_FILE)
SERVICES       := application-service document-service workflow-service audit-service
VERSION        := $(shell sed -n 's|.*<version>\(.*\)</version>.*|\1|p' pom.xml | sed -n 3p)
GIT_SHA        := $(shell git rev-parse --short HEAD 2>/dev/null || echo unknown)

# -----------------------------------------------------------------------------
# Help
# -----------------------------------------------------------------------------
.PHONY: help
help: ## Show this help
	@echo "AWS Loan Origination Platform - $(VERSION) ($(GIT_SHA))"
	@echo ""
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) \
		| awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-22s\033[0m %s\n", $$1, $$2}'
	@echo ""
	@echo "No target in this file deploys anything or touches an AWS account."

# -----------------------------------------------------------------------------
# Build and test
# -----------------------------------------------------------------------------
.PHONY: build
build: ## Compile everything and run unit tests (no Docker required)
	$(MVN) -B clean install -Pfast

.PHONY: test
test: ## Run unit tests only (fast, no Docker required)
	$(MVN) -B test -Pfast

.PHONY: verify
verify: ## Full build: unit + integration tests (requires a running Docker daemon)
	$(MVN) -B clean verify

.PHONY: test-e2e
test-e2e: ## Run the end-to-end scenarios (requires a running Docker daemon)
	$(MVN) -B verify -pl tests/end-to-end -am -DskipTests

.PHONY: format
format: ## Apply the formatting rules in place
	$(MVN) -B spotless:apply

.PHONY: format-check
format-check: ## Fail if any file violates the formatting rules
	$(MVN) -B spotless:check

.PHONY: analysis
analysis: ## Run static analysis
	$(MVN) -B verify -Panalysis -DskipTests -DskipITs

.PHONY: sbom
sbom: ## Generate a CycloneDX SBOM for every module
	$(MVN) -B package -Psbom -DskipTests -DskipITs

# -----------------------------------------------------------------------------
# Container images (built locally; never pushed by this Makefile)
# -----------------------------------------------------------------------------
.PHONY: docker-build
docker-build: ## Build every service image locally (no push)
	@for svc in $(SERVICES); do \
		echo "==> building $$svc"; \
		docker build \
			--build-arg SERVICE_NAME=$$svc \
			--build-arg VERSION=$(VERSION) \
			--build-arg GIT_SHA=$(GIT_SHA) \
			-f deploy/docker/service.Dockerfile \
			-t los/$$svc:$(VERSION) . || exit 1; \
	done

# -----------------------------------------------------------------------------
# Local environment
# -----------------------------------------------------------------------------
.PHONY: local-up
local-up: ## Start the local platform (PostgreSQL, Kafka, LocalStack, all services)
	$(COMPOSE) up -d --wait
	@echo ""
	@echo "Local platform is up. API: http://localhost:8081/v1  Health: http://localhost:8081/actuator/health"

.PHONY: local-down
local-down: ## Stop the local platform, KEEPING all data volumes
	$(COMPOSE) down

.PHONY: local-clean
local-clean: ## Stop the local platform and DELETE all local data volumes
	@echo "This deletes local PostgreSQL, Kafka and LocalStack data."
	$(COMPOSE) down --volumes --remove-orphans

.PHONY: local-logs
local-logs: ## Follow the logs of every local service
	$(COMPOSE) logs -f

.PHONY: local-smoke-test
local-smoke-test: ## Run the happy path against the running local platform
	./scripts/local-smoke-test.sh

.PHONY: local-secrets
local-secrets: ## Generate gitignored local-only key material for development
	./scripts/generate-local-secrets.sh

# -----------------------------------------------------------------------------
# Infrastructure validation (never applies anything)
# -----------------------------------------------------------------------------
.PHONY: tf-validate
tf-validate: ## Format-check and validate Terraform WITHOUT contacting AWS
	./scripts/validate-terraform.sh

.PHONY: helm-validate
helm-validate: ## Lint and render the Helm charts, then validate the manifests
	./scripts/validate-helm.sh

.PHONY: openapi-validate
openapi-validate: ## Validate the OpenAPI 3.1 document
	npx --yes @redocly/cli@1.34.3 lint docs/api/openapi.yaml

# -----------------------------------------------------------------------------
# Security
# -----------------------------------------------------------------------------
.PHONY: secret-scan
secret-scan: ## Scan the working tree and the full Git history for secrets
	./scripts/secret-scan.sh

.PHONY: ci-validate
ci-validate: ## Lint the GitHub workflows and check every action is SHA-pinned
	@./scripts/check-action-pins.sh
	@command -v actionlint >/dev/null 2>&1 && actionlint || echo "    actionlint is not installed; SKIPPED (CI always runs it)."

.PHONY: release-check
release-check: ## Run every check required before considering public release
	$(MAKE) format-check verify tf-validate helm-validate openapi-validate ci-validate secret-scan
	@echo ""
	@echo "==> Documentation links"
	@./scripts/check-doc-links.sh
	@echo ""
	@echo "Automated checks passed. A HUMAN SECURITY REVIEW is still required:"
	@echo "see docs/public-release-checklist.md."
