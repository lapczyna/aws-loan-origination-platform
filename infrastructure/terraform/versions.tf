# =============================================================================
# Provider and Terraform version constraints, shared by every environment.
#
# Versions are pinned with ~> rather than left open. An unpinned provider means
# `terraform init` can pull a new major version months after the code was
# written, and the first sign of it is a plan that wants to replace a database.
# =============================================================================

terraform {
  # Lower bound only for Terraform itself: the language is stable and pinning an
  # exact version blocks security patches for the CLI.
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source = "hashicorp/aws"
      # Pinned to a minor line. Provider majors change resource schemas.
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}
