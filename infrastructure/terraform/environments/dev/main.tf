# =============================================================================
# Development environment.
#
# NOTHING HERE HAS BEEN APPLIED. No remote state exists, no AWS resource was
# created, and every identifier is a placeholder.
#
# COST WARNING
# ------------
# Even this cost-reduced environment runs an EKS control plane, MSK brokers, a
# NAT gateway and an RDS instance continuously. Applied as written it costs
# real money every hour whether or not anyone uses it. See
# docs/operations/cost.md before running anything.
#
# The `enable_deployment` variable defaults to FALSE. Nothing is created until
# somebody deliberately opts in, which is the difference between an accidental
# `terraform apply` costing nothing and costing several hundred dollars.
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

  # COST: one shared NAT gateway rather than three. Acceptable in development,
  # where a zone failure taking down egress is an inconvenience rather than an
  # incident. Never in production.
  single_nat_gateway = true

  # Flow logs off in development. They are the largest CloudWatch Logs
  # contributor and their investigative value is production-specific.
  enable_flow_logs = false
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

  log_retention_days        = 7
  access_log_retention_days = 30

  api_gateway_name  = "${var.environment}-los-api"
  api_gateway_stage = "v1"
  eks_cluster_name  = "${var.environment}-los"
  msk_cluster_name  = "${var.environment}-los-events"

  rds_instance_identifier = "${var.environment}-los"

  application_metric_namespace = "${var.environment}/LoanOrigination"

  # Development thresholds are looser: paging on a laptop-scale environment
  # trains people to ignore the alarm.
  api_5xx_threshold            = 20
  outbox_age_threshold_seconds = 900
}

# -----------------------------------------------------------------------------
# Data stores.
# -----------------------------------------------------------------------------
module "database" {
  # Both of these are deliberate DEVELOPMENT settings, and the suppression is
  # scoped here rather than in the module so that production -- which sets both
  # to true -- is still checked. If anyone turns them off in prod-example, the
  # scan fails, which is the whole point of not adding a global skip.
  #
  # checkov:skip=CKV_AWS_157:Multi-AZ is off in development. It doubles the instance charge continuously to protect against a zone failure that, here, is an inconvenience. prod-example sets multi_az = true.
  # checkov:skip=CKV_AWS_293:Deletion protection is off in development, because tearing the environment down and rebuilding it is a routine action. prod-example sets deletion_protection = true and skip_final_snapshot = false.

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

  instance_class    = "db.t4g.micro"
  allocated_storage = 20

  # COST: Multi-AZ doubles the instance charge. Off in development, where a zone
  # failure is an inconvenience. Never off in production.
  multi_az = false

  backup_retention_days = 7
  deletion_protection   = false
  skip_final_snapshot   = true
  apply_immediately     = true

  # A DR replica in development would cost as much as the primary and protect
  # nothing anyone would miss.
  enable_cross_region_dr_replica = false
}

module "documents_bucket" {
  source = "../../modules/s3-documents"
  count  = local.enabled

  environment = var.environment
  name        = "los-documents-${local.account_id}"
  kms_key_arn = module.kms_documents[0].key_arn

  quarantine_retention_days = 3

  # Development buckets may be emptied by a destroy. Never true in production.
  force_destroy = true
}

module "audit_bucket" {
  source = "../../modules/s3-audit"
  count  = local.enabled

  environment = var.environment
  name        = "los-audit-${local.account_id}"
  kms_key_arn = module.kms_audit[0].key_arn

  # Object Lock ON even in development. It cannot be enabled later, so a
  # development bucket without it cannot be used to rehearse the production
  # retention behaviour -- and rehearsing it is the point of having a
  # development environment.
  object_lock_enabled = true
  object_lock_mode    = "GOVERNANCE"
  # Short retention here; seven years in production.
  retention_days = 30
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

  # COST: the smallest supported broker, three of them, because
  # min.insync.replicas=2 with a replication factor of 3 is the configuration
  # being tested. Two brokers would not exercise it.
  broker_instance_type   = "kafka.t3.small"
  number_of_broker_nodes = 3
  broker_ebs_volume_size = 20

  # Cheaper monitoring in development: per-partition metrics are billed as
  # custom metrics and there is nothing here to diagnose.
  enhanced_monitoring = "PER_BROKER"
}

# -----------------------------------------------------------------------------
# The edge, inside the VPC.
#
# API Gateway -> VPC Link -> this NLB -> the ALB the Helm chart creates -> pods.
# The NLB exists because a REST API's VPC Link accepts a network load balancer
# and nothing else; the ALB exists because path routing is layer 7.
# -----------------------------------------------------------------------------
module "internal_load_balancer" {
  # A deliberate DEVELOPMENT setting, and the suppression is scoped here rather
  # than in the module so that production -- which leaves deletion protection on
  # -- is still checked. If anyone turns it off in prod-example, the scan fails.
  #
  # checkov:skip=CKV_AWS_150:Deletion protection is off in development, where tearing the environment down and rebuilding it is routine. prod-example sets enable_deletion_protection = true.

  source = "../../modules/internal-load-balancer"
  count  = local.enabled

  environment        = var.environment
  vpc_id             = module.networking[0].vpc_id
  vpc_cidr           = module.networking[0].vpc_cidr
  private_subnet_ids = module.networking[0].private_subnet_ids

  # The VPC Link's network interfaces live in this VPC and AWS exposes no
  # security group for them, so this is a CIDR rule -- narrowed to the VPC and
  # to the listener port.
  allowed_source_cidrs = [module.networking[0].vpc_cidr]

  # TLS terminates here as well as at the edge. Also the only way an NLB writes
  # access logs at all.
  enable_tls      = var.internal_tls_certificate_arn != null
  certificate_arn = var.internal_tls_certificate_arn

  # Null on a first apply: the ALB does not exist until the Helm chart is
  # installed, which needs the cluster this module's outputs help create.
  target_arn = var.internal_alb_arn

  enable_deletion_protection = false
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

  # The nodes accept traffic from this load balancer and from nothing else.
  load_balancer_security_group_ids = [module.internal_load_balancer[0].security_group_id]

  # COST: the smallest instances that will run the four services, and two
  # nodes rather than three. A zone failure in development is an
  # inconvenience.
  node_instance_types = ["t4g.medium"]
  node_desired_size   = 2
  node_min_size       = 2
  node_max_size       = 4
}

# -----------------------------------------------------------------------------
# Per-service identities.
#
# One IAM role per service, bound to its Kubernetes ServiceAccount by an EKS Pod
# Identity association. A shared role would give every pod the union of all four
# services' permissions.
# -----------------------------------------------------------------------------
module "iam" {
  source = "../../modules/iam"
  count  = local.enabled

  environment = var.environment
  region      = var.region
  account_id  = local.account_id

  eks_cluster_name = module.eks[0].cluster_name

  # The resource id, not the identifier: an ARN built from the name grants
  # nothing, and the failure looks like a network problem.
  db_resource_id  = module.database[0].resource_id
  msk_cluster_arn = module.events[0].cluster_arn

  documents_bucket_arn = module.documents_bucket[0].bucket_arn
  audit_bucket_arn     = module.audit_bucket[0].bucket_arn

  documents_kms_key_arn = module.kms_documents[0].key_arn
  audit_kms_key_arn     = module.kms_audit[0].key_arn
  # The Secrets Manager entries the services mount are encrypted with the
  # database key, which is also what RDS uses for its managed master secret.
  secrets_kms_key_arn = module.kms_database[0].key_arn

  metric_namespace = "${var.environment}/LoanOrigination"
}

# -----------------------------------------------------------------------------
# The public edge.
#
# Everything reaching the platform passes through here: WAF, the JWT authorizer,
# throttling, usage plans and access logging. The load balancer behind it is
# internal, so there is no route that bypasses this.
# -----------------------------------------------------------------------------
module "api_gateway" {
  source = "../../modules/api-gateway"
  count  = local.enabled

  environment = var.environment
  stage_name  = "v1"

  # PLACEHOLDER. A real user-pool ARN names the account and the pool.
  cognito_user_pool_arns = var.cognito_user_pool_arns

  internal_nlb_arn = module.internal_load_balancer[0].arn
  internal_nlb_url = module.internal_load_balancer[0].integration_url

  access_log_group_arn = module.cloudwatch[0].api_gateway_access_log_group_arn
  waf_log_group_arn    = module.cloudwatch[0].waf_log_group_arn

  waf_enabled     = false
  require_api_key = false
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

  tagged_image_count = 10
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
