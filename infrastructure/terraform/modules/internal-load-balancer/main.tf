# =============================================================================
# The internal Network Load Balancer.
#
# The one hop between API Gateway's VPC Link and the cluster.
#
# WHY AN NLB WHEN THE HELM CHART ALREADY CREATES AN ALB
# -----------------------------------------------------
# Because two constraints meet here and neither bends:
#
#   * An API Gateway REST API's VPC Link accepts a NETWORK load balancer and
#     nothing else. It cannot target an ALB.
#   * Routing /v1/applications to one service and
#     /v1/applications/{id}/documents to another is path-based, which is layer 7.
#     An NLB cannot do it.
#
# So the chain is NLB -> ALB -> pods: this module owns the NLB, because the VPC
# Link needs an ARN that exists before the cluster does; the AWS Load Balancer
# Controller owns the ALB, because path rules belong with the Ingress that
# declares them.
#
# The extra hop is a real cost -- a few milliseconds and one more load balancer
# billed hourly. The alternative is an ingress controller inside the cluster
# doing the path routing, which trades the hop for a component to operate. Either
# is defensible; this one keeps the routing rules next to the services they route
# to.
#
# THIS IS INTERNAL, AND THAT IS THE POINT. Private addresses only, so the only
# way in is through API Gateway's VPC Link -- and therefore through WAF, the JWT
# authorizer, throttling, usage plans and access logging. A public load balancer
# here bypasses every one of them, silently, while everything still appears to
# work. The variable that would allow it does not exist.
#
# NOTHING HERE HAS BEEN APPLIED.
# =============================================================================

locals {
  name = "${var.environment}-los-internal"

  common_tags = merge(var.tags, {
    Module = "internal-load-balancer"
  })
}

# -----------------------------------------------------------------------------
# Security group.
#
# Network Load Balancers have supported security groups since 2023. Before that
# an NLB was transparent and the only enforcement point was the target's own
# group, which meant "who may reach this load balancer" had no single answer.
#
# It cannot be added to an existing NLB, so it is set at creation or never.
# -----------------------------------------------------------------------------
resource "aws_security_group" "this" {
  name        = local.name
  description = "Internal NLB for ${local.name}"
  vpc_id      = var.vpc_id

  tags = merge(local.common_tags, { Name = local.name })

  lifecycle {
    create_before_destroy = true
  }
}

# Ingress from the VPC Link's network interfaces.
#
# Those interfaces live in this VPC, in the subnets the VPC Link is given, and
# AWS does not expose a security group for them -- so this is a CIDR rule, which
# is the one place in this platform where that is unavoidable. It is narrowed to
# the VPC rather than left open, and the listener port only.
resource "aws_vpc_security_group_ingress_rule" "from_vpc_link" {
  for_each = toset(var.allowed_source_cidrs)

  security_group_id = aws_security_group.this.id
  cidr_ipv4         = each.value
  from_port         = var.listener_port
  to_port           = var.listener_port
  ip_protocol       = "tcp"
  description       = "API Gateway VPC Link to the listener"

  tags = local.common_tags
}

# Egress to the targets only, on the target port. An NLB that can reach anything
# in the VPC is a pivot point; this one can reach the ALB and nothing else.
resource "aws_vpc_security_group_egress_rule" "to_targets" {
  security_group_id = aws_security_group.this.id
  cidr_ipv4         = var.vpc_cidr
  from_port         = var.target_port
  to_port           = var.target_port
  ip_protocol       = "tcp"
  description       = "Load balancer to its targets"

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# The load balancer.
# -----------------------------------------------------------------------------
resource "aws_lb" "this" {
  name               = local.name
  load_balancer_type = "network"

  # Hard-coded true. There is no variable for this, deliberately: an
  # internet-facing load balancer here bypasses the entire edge, and a value
  # somebody can flip in a tfvars file is not a control. The Helm chart refuses
  # the same thing for the same reason.
  internal = true

  subnets         = var.private_subnet_ids
  security_groups = [aws_security_group.this.id]

  # COST: cross-zone load balancing on an NLB is billed for every byte that
  # crosses an Availability Zone boundary, and it is off by default.
  #
  # On by default HERE because the alternative is worse: with it off, each zone's
  # load balancer node sends only to targets in its own zone, so a zone holding
  # one pod receives the same share of traffic as a zone holding four. The Helm
  # chart's topology spread constraints make the pod distribution even, not the
  # traffic distribution.
  enable_cross_zone_load_balancing = var.enable_cross_zone_load_balancing

  # Refuses `terraform destroy` until somebody deliberately turns it off. The
  # last line of defence against a destroy that was meant for another workspace.
  enable_deletion_protection = var.enable_deletion_protection

  # Drops invalid HTTP headers rather than forwarding them. Relevant even at
  # layer 4 for the TLS listener, and free.
  drop_invalid_header_fields = true

  # ---------------------------------------------------------------------------
  # Access logs.
  #
  # A NON-OBVIOUS CONSTRAINT worth writing down: an NLB only writes access logs
  # for TLS listeners. With a plain TCP listener the setting is accepted, the
  # bucket stays empty, and nobody notices until an investigation needs the logs
  # and they are not there.
  #
  # That is a second reason the listener terminates TLS rather than passing TCP
  # through.
  # ---------------------------------------------------------------------------
  dynamic "access_logs" {
    for_each = var.access_logs_bucket == null ? [] : [1]

    content {
      bucket  = var.access_logs_bucket
      prefix  = "${local.name}/nlb"
      enabled = true
    }
  }

  tags = merge(local.common_tags, { Name = local.name })
}

# -----------------------------------------------------------------------------
# Target group.
#
# Type `alb`, so the NLB forwards to the ALB the Load Balancer Controller creates
# from the chart's Ingress. That is what makes path-based routing possible behind
# a layer-4 load balancer.
#
# `ip` is offered as an alternative for a deployment that puts an ingress
# controller inside the cluster instead; the pods are then registered by a
# TargetGroupBinding referencing this group's ARN.
# -----------------------------------------------------------------------------
resource "aws_lb_target_group" "this" {
  name        = local.name
  vpc_id      = var.vpc_id
  target_type = var.target_type
  port        = var.target_port
  protocol    = "TCP"

  # ---------------------------------------------------------------------------
  # Health checks against the READINESS endpoint, not liveness and not "/".
  #
  # Readiness includes the database, so the load balancer's view of health
  # matches Kubernetes'. Checking liveness instead would send traffic to a pod
  # that is running but cannot serve, and checking "/" would report healthy for a
  # service whose dependencies are all down.
  # ---------------------------------------------------------------------------
  health_check {
    protocol            = "HTTP"
    path                = var.health_check_path
    port                = "traffic-port"
    healthy_threshold   = 2
    unhealthy_threshold = 2
    interval            = 10
    timeout             = 6
    matcher             = "200"
  }

  # Long enough for an in-flight request to finish, short enough that a rolling
  # deployment is not held open by a target nothing is using. Must be shorter
  # than the pod's terminationGracePeriodSeconds or the pod is killed while the
  # load balancer still believes it is draining.
  deregistration_delay = var.deregistration_delay_seconds

  # Off. With `ip` targets the preserved address would be the pod's, and with
  # `alb` targets it is the load balancer's -- neither is the caller. The real
  # client address arrives as an X-Forwarded-For header set at API Gateway, and
  # is recorded in its access log.
  preserve_client_ip = "false"

  tags = local.common_tags

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# Registering the target.
#
# SEPARATE, AND OPTIONAL, BECAUSE OF AN ORDERING PROBLEM THAT IS EASY TO HIT.
# The ALB does not exist when this module is first applied: the AWS Load Balancer
# Controller creates it from the chart's Ingress, which needs a cluster, which
# needs this module's outputs. So the ARN cannot be known on the first apply.
#
# Leaving it null creates the load balancer and its target group with nothing
# registered -- which is not a broken state, it is an empty one. Supply the ARN
# on a second apply, once the chart is installed. Recorded here rather than
# discovered.
# -----------------------------------------------------------------------------
resource "aws_lb_target_group_attachment" "this" {
  count = var.target_arn == null ? 0 : 1

  target_group_arn = aws_lb_target_group.this.arn
  target_id        = var.target_arn
  port             = var.target_port
}

# -----------------------------------------------------------------------------
# Listener.
#
# TLS, not TCP. The hop is inside the VPC, which is exactly the reasoning that
# normalises unencrypted internal traffic -- and it would also mean no access
# logs, since an NLB writes them for TLS listeners only.
# -----------------------------------------------------------------------------
resource "aws_lb_listener" "this" {
  load_balancer_arn = aws_lb.this.arn
  port              = var.listener_port
  protocol          = var.enable_tls ? "TLS" : "TCP"

  certificate_arn = var.enable_tls ? var.certificate_arn : null
  # TLS 1.3 and 1.2 only. The policies that also accept 1.0 and 1.1 exist for
  # clients that do not apply here: the only caller is API Gateway.
  ssl_policy = var.enable_tls ? var.ssl_policy : null

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.this.arn
  }

  tags = local.common_tags

  lifecycle {
    # A TLS listener with no certificate is rejected by the API with a message
    # that does not say which of several things is missing. This says it.
    precondition {
      condition     = !var.enable_tls || var.certificate_arn != null
      error_message = "certificate_arn is required when enable_tls is true. Without TLS the NLB also writes no access logs at all."
    }
  }
}
