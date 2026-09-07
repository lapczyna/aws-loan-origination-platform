# =============================================================================
# VPC, subnets and egress.
#
# Three tiers across three Availability Zones:
#
#   public    NAT gateways and the API Gateway VPC Link's network interfaces
#   private   EKS nodes and every application workload
#   data      RDS and MSK, with no route to a NAT gateway at all
#
# The data tier's lack of egress is the point. A database or a broker has no
# legitimate reason to initiate an outbound connection, and removing the route
# removes an exfiltration path entirely rather than relying on a rule someone
# could later relax.
#
# COST WARNING: NAT gateways are billed hourly PER GATEWAY plus per gigabyte
# processed, and they are among the largest line items on a small platform. One
# per Availability Zone is correct for production -- a single shared gateway
# makes one zone failure take down egress everywhere -- but it triples the
# hourly charge, which is why single_nat_gateway exists for development.
#
# NOTHING HERE HAS BEEN APPLIED.
# =============================================================================

locals {
  name        = "${var.environment}-los"
  common_tags = merge(var.tags, { Module = "networking" })

  nat_gateway_count = var.single_nat_gateway ? 1 : length(var.availability_zones)
}

resource "aws_vpc" "this" {
  cidr_block = var.vpc_cidr

  # Both required for private DNS on VPC endpoints and for RDS hostnames to
  # resolve inside the VPC.
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(local.common_tags, { Name = local.name })
}

# -----------------------------------------------------------------------------
# The default security group.
#
# Every VPC gets one, and AWS creates it permitting all traffic between members.
# It cannot be deleted. Anything launched without an explicit security group
# lands in it, which means a forgotten security group silently becomes
# "unrestricted within the VPC".
#
# Managing it here with NO rules turns that default from permissive to closed:
# an instance that ends up in it can talk to nothing at all, which is loud and
# obvious rather than quietly over-permissive.
# -----------------------------------------------------------------------------
resource "aws_default_security_group" "this" {
  vpc_id = aws_vpc.this.id

  # No ingress and no egress blocks. Their absence is the configuration: it
  # removes the rules AWS created.

  tags = merge(local.common_tags, {
    Name = "${local.name}-default-DO-NOT-USE"
  })
}

# -----------------------------------------------------------------------------
# Flow logs.
#
# The record of what actually talked to what. It is the first thing anyone asks
# for during a security investigation, and it cannot be reconstructed afterwards
# if it was not being captured at the time.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "flow_logs" {
  count = var.enable_flow_logs ? 1 : 0

  name              = "/aws/vpc/${local.name}/flow-logs"
  retention_in_days = var.flow_log_retention_days
  kms_key_id        = var.kms_key_arn

  tags = local.common_tags
}

data "aws_iam_policy_document" "flow_logs_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["vpc-flow-logs.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "flow_logs" {
  count = var.enable_flow_logs ? 1 : 0

  name               = "${local.name}-flow-logs"
  assume_role_policy = data.aws_iam_policy_document.flow_logs_assume_role.json
  tags               = local.common_tags
}

data "aws_iam_policy_document" "flow_logs" {
  count = var.enable_flow_logs ? 1 : 0

  statement {
    effect = "Allow"
    actions = [
      "logs:CreateLogStream",
      "logs:PutLogEvents",
      "logs:DescribeLogGroups",
      "logs:DescribeLogStreams",
    ]
    # Scoped to this log group rather than "*". A flow-log role that can write
    # anywhere could also be used to forge entries in another log group.
    resources = ["${aws_cloudwatch_log_group.flow_logs[0].arn}:*"]
  }
}

resource "aws_iam_role_policy" "flow_logs" {
  count = var.enable_flow_logs ? 1 : 0

  name   = "${local.name}-flow-logs"
  role   = aws_iam_role.flow_logs[0].id
  policy = data.aws_iam_policy_document.flow_logs[0].json
}

resource "aws_flow_log" "this" {
  count = var.enable_flow_logs ? 1 : 0

  vpc_id = aws_vpc.this.id
  # ALL, not REJECT. REJECT alone would miss successful exfiltration; ACCEPT
  # alone would miss blocked reconnaissance. ALL costs more and is the only
  # setting that answers both questions.
  traffic_type             = "ALL"
  log_destination_type     = "cloud-watch-logs"
  log_destination          = aws_cloudwatch_log_group.flow_logs[0].arn
  iam_role_arn             = aws_iam_role.flow_logs[0].arn
  max_aggregation_interval = 60

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# Subnets.
# -----------------------------------------------------------------------------
resource "aws_subnet" "public" {
  count = length(var.availability_zones)

  vpc_id            = aws_vpc.this.id
  availability_zone = var.availability_zones[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index)

  # Never true. An instance that receives a public address automatically is an
  # instance exposed by default, and the only things here are NAT gateways and
  # load balancer interfaces, which manage their own addressing.
  map_public_ip_on_launch = false

  tags = merge(local.common_tags, {
    Name                     = "${local.name}-public-${var.availability_zones[count.index]}"
    Tier                     = "public"
    "kubernetes.io/role/elb" = "1"
  })
}

resource "aws_subnet" "private" {
  count = length(var.availability_zones)

  vpc_id            = aws_vpc.this.id
  availability_zone = var.availability_zones[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 4)

  tags = merge(local.common_tags, {
    Name                              = "${local.name}-private-${var.availability_zones[count.index]}"
    Tier                              = "private"
    "kubernetes.io/role/internal-elb" = "1"
  })
}

resource "aws_subnet" "data" {
  count = length(var.availability_zones)

  vpc_id            = aws_vpc.this.id
  availability_zone = var.availability_zones[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 8)

  tags = merge(local.common_tags, {
    Name = "${local.name}-data-${var.availability_zones[count.index]}"
    Tier = "data"
  })
}

# -----------------------------------------------------------------------------
# Internet and NAT.
# -----------------------------------------------------------------------------
resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(local.common_tags, { Name = local.name })
}

resource "aws_eip" "nat" {
  count = local.nat_gateway_count

  domain = "vpc"
  tags   = merge(local.common_tags, { Name = "${local.name}-nat-${count.index}" })

  depends_on = [aws_internet_gateway.this]
}

resource "aws_nat_gateway" "this" {
  count = local.nat_gateway_count

  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id

  tags = merge(local.common_tags, { Name = "${local.name}-nat-${count.index}" })

  depends_on = [aws_internet_gateway.this]
}

# -----------------------------------------------------------------------------
# Routing.
# -----------------------------------------------------------------------------
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = merge(local.common_tags, { Name = "${local.name}-public" })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count = length(aws_subnet.public)

  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# One route table per private subnet, so each Availability Zone egresses through
# its own NAT gateway. A shared table would funnel every zone's traffic through
# one gateway, making it a single point of failure and adding a cross-zone
# transfer charge to every byte.
resource "aws_route_table" "private" {
  count = length(var.availability_zones)

  vpc_id = aws_vpc.this.id
  tags   = merge(local.common_tags, { Name = "${local.name}-private-${count.index}" })
}

resource "aws_route" "private_nat" {
  count = length(var.availability_zones)

  route_table_id         = aws_route_table.private[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[var.single_nat_gateway ? 0 : count.index].id
}

resource "aws_route_table_association" "private" {
  count = length(aws_subnet.private)

  subnet_id      = aws_subnet.private[count.index].id
  route_table_id = aws_route_table.private[count.index].id
}

# The data tier's route table has NO default route. RDS and MSK cannot reach the
# internet at all, in either direction.
resource "aws_route_table" "data" {
  vpc_id = aws_vpc.this.id
  tags   = merge(local.common_tags, { Name = "${local.name}-data" })
}

resource "aws_route_table_association" "data" {
  count = length(aws_subnet.data)

  subnet_id      = aws_subnet.data[count.index].id
  route_table_id = aws_route_table.data.id
}

# -----------------------------------------------------------------------------
# VPC endpoints.
#
# S3 traffic uses a gateway endpoint rather than the NAT gateway. Both a
# security improvement -- the traffic never leaves the AWS network -- and a
# significant saving, because document uploads and audit exports would otherwise
# be billed as NAT data processing.
# -----------------------------------------------------------------------------
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"

  route_table_ids = concat(
    aws_route_table.private[*].id,
    [aws_route_table.data.id],
  )

  tags = merge(local.common_tags, { Name = "${local.name}-s3" })
}
