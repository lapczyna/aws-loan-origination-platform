terraform {
  required_version = ">= 1.9.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    # Reads the OIDC issuer's certificate to compute the thumbprint IAM needs
    # when registering the provider for IRSA.
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}
