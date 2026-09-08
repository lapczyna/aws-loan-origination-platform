# =============================================================================
# Production EXAMPLE.
#
# THIS HAS NEVER BEEN APPLIED, AND THE NAME IS DELIBERATE. It illustrates what a
# production configuration looks like; it is not a configuration to apply
# unreviewed.
#
# COST WARNING -- READ BEFORE ENABLING ANYTHING
# ---------------------------------------------
# Applied as written, this costs SEVERAL HUNDRED US DOLLARS PER MONTH before a
# single loan application is submitted. The dominant items:
#
#   EKS control plane          fixed hourly charge, continuously
#   MSK, 3 x m7g.large         the largest line item, continuously
#   NAT gateways, 3            hourly each, plus per-GB processed
#   RDS Multi-AZ               double the instance charge, continuously
#   DR replica (if enabled)    roughly doubles the database bill again
#
# See docs/operations/cost.md for the full breakdown and the levers.
#
# `enable_deployment` defaults to FALSE, so nothing is created until somebody
# deliberately opts in.
# =============================================================================

terraform {
  required_version = ">= 1.9.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }

  # NO BACKEND BLOCK, DELIBERATELY.
  #
  # State contains resource attributes and, for some resources, secret values. A
  # backend block naming a real bucket publishes that bucket's name and the
  # account it lives in. Bootstrapping the backend is a documented one-time step:
  # see docs/operations/terraform-state-bootstrap.md.
  #
  # This also lets `terraform init -backend=false` validate the configuration
  # with no credentials and no network call.
}

provider "aws" {
  region = var.region

  default_tags {
    # Applied to every resource that supports tagging. Cost allocation, the
    # budget filter and every "what does this belong to?" question depend on
    # these being present and consistent.
    tags = {
      Project     = "loan-origination-platform"
      Environment = var.environment
      ManagedBy   = "terraform"
      Owner       = var.owner_tag
      CostCentre  = var.cost_centre_tag
      Repository  = "aws-loan-origination-platform"
    }
  }
}

# The DR region's provider. Configured even when the replica is disabled,
# because the RDS module declares the alias as required -- a caller that enables
# the replica without it would otherwise create the replica in the wrong region.
provider "aws" {
  alias  = "dr"
  region = var.dr_region

  default_tags {
    tags = {
      Project     = "loan-origination-platform"
      Environment = var.environment
      ManagedBy   = "terraform"
      Owner       = var.owner_tag
      CostCentre  = var.cost_centre_tag
      Repository  = "aws-loan-origination-platform"
      Role        = "disaster-recovery"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  # Everything is gated on this. With enable_deployment false the count on every
  # module is zero, so a plan produces no resources and an apply creates
  # nothing.
  enabled = var.enable_deployment ? 1 : 0

  # Three zones. Two leaves no quorum after losing one.
  availability_zones = slice(data.aws_availability_zones.available.names, 0, 3)

  account_id = data.aws_caller_identity.current.account_id
}

# -----------------------------------------------------------------------------
# Encryption keys.
#
# Separate keys per data domain rather than one key for everything. A single key
# means a single key policy, and "who can decrypt the audit trail" then cannot
# be answered differently from "who can decrypt the documents".
# -----------------------------------------------------------------------------
module "kms_database" {
  source = "../../modules/kms"
  count  = local.enabled

  alias_name  = "${var.environment}-los-database"
  description = "Encrypts the loan origination database, its backups and its Performance Insights data"

  service_principals = ["rds.amazonaws.com"]
  via_services       = ["rds.${var.region}.amazonaws.com"]
}

module "kms_documents" {
  source = "../../modules/kms"
  count  = local.enabled

  alias_name  = "${var.environment}-los-documents"
  description = "Encrypts uploaded customer documents"

  service_principals = ["s3.amazonaws.com"]
  via_services       = ["s3.${var.region}.amazonaws.com"]
}

module "kms_audit" {
  source = "../../modules/kms"
  count  = local.enabled

  alias_name  = "${var.environment}-los-audit"
  description = "Encrypts the audit trail. Deliberately separate from the documents key."

  service_principals = ["s3.amazonaws.com"]
  via_services       = ["s3.${var.region}.amazonaws.com"]
}

module "kms_observability" {
  source = "../../modules/kms"
  count  = local.enabled

  alias_name  = "${var.environment}-los-observability"
  description = "Encrypts CloudWatch log groups, the alarm topic and MSK data at rest"

  service_principals = ["logs.amazonaws.com", "sns.amazonaws.com", "kafka.amazonaws.com"]
  via_services = [
    "logs.${var.region}.amazonaws.com",
    "sns.${var.region}.amazonaws.com",
    "kafka.${var.region}.amazonaws.com",
  ]
}

# -----------------------------------------------------------------------------
# Networking.
# -----------------------------------------------------------------------------
module "networking" {
  source = "../../modules/networking"
  count  = local.enabled

  environment        = var.environment
  region             = var.region
  vpc_cidr           = var.vpc_cidr
  availability_zones = local.availability_zones
  kms_key_arn        = module.kms_observability[0].key_arn

  # One NAT gateway per Availability Zone. Sharing one makes a single zone
  # failure take down egress everywhere, which is not a production posture.
  # COST: triples the hourly NAT charge.
  single_nat_gateway = false

  # Flow logs on. They are the record of what actually talked to what, they
  # cannot be reconstructed afterwards, and they are the first thing anyone asks
  # for during a security investigation.
  enable_flow_logs        = true
  flow_log_retention_days = 90
}

# -----------------------------------------------------------------------------
# Observability. Created before the resources that reference its log groups.
# -----------------------------------------------------------------------------
module "cloudwatch" {
  source = "../../modules/cloudwatch"
  count  = local.enabled

  environment = var.environment
  account_id  = local.account_id
  kms_key_arn = module.kms_observability[0].key_arn

  log_retention_days        = 30
  access_log_retention_days = 365

  api_gateway_name  = "${var.environment}-los-api"
  api_gateway_stage = "v1"
  eks_cluster_name  = "${var.environment}-los"
  msk_cluster_name  = "${var.environment}-los-events"

  rds_instance_identifier = "${var.environment}-los"

  application_metric_namespace = "${var.environment}/LoanOrigination"

  # Production thresholds. Each is a statement about what a customer notices.
  api_5xx_threshold            = 5
  api_latency_p99_threshold_ms = 3000
  outbox_age_threshold_seconds = 300
  stuck_applications_threshold = 5

  # The replica-lag alarm exists only when the replica does.
  enable_cross_region_dr_replica = var.enable_cross_region_dr_replica
  rds_dr_replica_identifier      = "${var.environment}-los-dr"
}

# -----------------------------------------------------------------------------
# Data stores.
# -----------------------------------------------------------------------------
module "database" {
  source = "../../modules/rds-postgresql"
  count  = local.enabled

  providers = {
    aws.dr = aws.dr
  }

  environment        = var.environment
  vpc_id             = module.networking[0].vpc_id
  private_subnet_ids = module.networking[0].data_subnet_ids
  kms_key_arn        = module.kms_database[0].key_arn

  # The EKS nodes, and nothing else. A security-group grant follows the
  # workload; a CIDR grant would follow whatever holds an address in that range
  # now or later.
  allowed_security_group_ids = [module.eks[0].node_security_group_id]

  instance_class        = "db.r7g.large"
  allocated_storage     = 200
  max_allocated_storage = 1000

  # Synchronous standby in a second Availability Zone: zero data loss on
  # failover. COST: doubles the instance charge, continuously.
  multi_az = true

  backup_retention_days = 30

  # Both on. Deletion protection refuses a destroy outright; the final snapshot
  # means even a deliberate destroy is recoverable.
  deletion_protection = true
  skip_final_snapshot = false

  # Changes wait for the maintenance window rather than rebooting the primary in
  # the middle of the afternoon.
  apply_immediately = false

  # Off by default even here. A cross-region replica roughly DOUBLES the
  # database bill and adds cross-region transfer charges. It is NOT a backup --
  # it replicates a mistaken DELETE faithfully -- and NOT zero RPO, because it
  # lags.
  enable_cross_region_dr_replica = var.enable_cross_region_dr_replica
  dr_replica_instance_class      = "db.r7g.large"
  dr_replica_multi_az            = true
}

module "documents_bucket" {
  source = "../../modules/s3-documents"
  count  = local.enabled

  environment = var.environment
  name        = "los-documents-${local.account_id}"
  kms_key_arn = module.kms_documents[0].key_arn

  quarantine_retention_days = 7

  # Never true. These are customer documents.
  force_destroy = false
}

module "audit_bucket" {
  source = "../../modules/s3-audit"
  count  = local.enabled

  environment = var.environment
  name        = "los-audit-${local.account_id}"
  kms_key_arn = module.kms_audit[0].key_arn

  object_lock_enabled = true
  # GOVERNANCE unless a regulator requires otherwise. COMPLIANCE cannot be
  # undone by anyone -- including the root user and AWS support -- for the full
  # seven years, and that is a commitment to make deliberately rather than
  # inherit from a template.
  object_lock_mode = "GOVERNANCE"
  retention_days   = 2555
}

module "events" {
  source = "../../modules/msk"
  count  = local.enabled

  environment                = var.environment
  vpc_id                     = module.networking[0].vpc_id
  private_subnet_ids         = module.networking[0].data_subnet_ids
  allowed_security_group_ids = [module.eks[0].node_security_group_id]
  kms_key_arn                = module.kms_observability[0].key_arn
  broker_log_group_name      = module.cloudwatch[0].msk_broker_log_group_name

  # COST: the largest single line item on the platform. Three brokers run
  # continuously whether or not any message is published.
  broker_instance_type       = "kafka.m7g.large"
  number_of_broker_nodes     = 3
  broker_ebs_volume_size     = 500
  broker_ebs_max_volume_size = 2000

  # Per-partition metrics, because that is what makes consumer lag visible per
  # partition. Coarser levels report cluster totals, which cannot tell "one
  # consumer is stuck" from "everything is slightly behind".
  enhanced_monitoring = "PER_TOPIC_PER_PARTITION"
}

# -----------------------------------------------------------------------------
# Kubernetes.
#
# COST: the control plane is billed hourly at a fixed rate whether or not a pod
# is running, and the nodes are billed per instance-hour on top. Neither scales
# to zero.
# -----------------------------------------------------------------------------
module "eks" {
  source = "../../modules/eks"
  count  = local.enabled

  environment        = var.environment
  vpc_id             = module.networking[0].vpc_id
  private_subnet_ids = module.networking[0].private_subnet_ids

  kubernetes_version = "1.31"

  # The cluster's own key encrypts Kubernetes Secrets and the node volumes; the
  # observability key encrypts the control plane log group, which is where the
  # audit log goes.
  kms_key_arn     = module.kms_database[0].key_arn
  log_kms_key_arn = module.kms_observability[0].key_arn

  # The API server has no public endpoint. Reach it through SSM Session Manager
  # or a VPN.
  endpoint_public_access = false

  cluster_admin_role_arns = var.cluster_admin_role_arns

  # Three nodes, one per Availability Zone, so the Helm chart's topology
  # spread constraints and PodDisruptionBudgets are satisfiable during a
  # rolling update and after losing a zone.
  node_instance_types = ["m7g.large"]
  node_desired_size   = 3
  node_min_size       = 3
  node_max_size       = 8
}

# -----------------------------------------------------------------------------
# Container registry.
# -----------------------------------------------------------------------------
module "ecr" {
  source = "../../modules/ecr"
  count  = local.enabled

  environment = var.environment
  account_id  = local.account_id
  kms_key_arn = module.kms_observability[0].key_arn

  tagged_image_count = 30
}

# -----------------------------------------------------------------------------
# Cost guardrail.
#
# Off by default, and refuses to create anything without both an explicit amount
# and a notification address.
# -----------------------------------------------------------------------------
module "budget" {
  source = "../../modules/budgets"
  count  = local.enabled

  enabled     = var.enable_budget
  environment = var.environment

  monthly_limit_amount         = var.budget_monthly_limit
  notification_email_addresses = var.budget_notification_emails
}
