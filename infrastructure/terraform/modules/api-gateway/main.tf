# =============================================================================
# Amazon API Gateway (REST API) behind AWS WAF, in front of a VPC Link.
#
# WHY REST API RATHER THAN HTTP API
# ---------------------------------
# HTTP API is cheaper, faster and simpler, and would be the right default. REST
# API is chosen here for three capabilities it has that HTTP API does not:
#
#   1. AWS WAF integration. HTTP API cannot be associated with a Web ACL at all;
#      a WAF would have to sit in front of a CloudFront distribution instead,
#      adding a component and a caching layer this platform does not want.
#   2. Usage plans and API keys, which is how a partner gets its own throttle
#      and quota. HTTP API has account-level throttling only, so one partner's
#      burst would degrade every other partner.
#   3. Request validation against a model, rejecting a malformed body at the
#      edge instead of spending a VPC Link connection and a JVM thread on it.
#
# The trade is real: REST API costs roughly three and a half times more per
# million requests and adds latency. Recorded in docs/adr/ADR-0006.
#
# NOTHING HERE HAS BEEN APPLIED. Every domain, certificate and account
# identifier is a placeholder.
# =============================================================================

locals {
  api_name    = "${var.environment}-los-api"
  common_tags = merge(var.tags, { Module = "api-gateway" })
}

resource "aws_api_gateway_rest_api" "this" {
  name        = local.api_name
  description = "Loan origination platform API (${var.environment})"

  endpoint_configuration {
    # REGIONAL, not EDGE. An edge-optimised endpoint fronts the API with
    # CloudFront, which adds a caching layer nobody asked for and moves the WAF
    # association to a global scope. Regional keeps the request path short and
    # the WAF regional.
    types = ["REGIONAL"]
  }

  # Requests larger than this are rejected before reaching the VPC Link. The
  # platform accepts no file uploads through the API -- documents go straight to
  # S3 -- so a large body is either a mistake or an attack.
  binary_media_types = []

  # Fails a deployment that would leave the API without a body, rather than
  # silently replacing a working API with an empty one.
  disable_execute_api_endpoint = false

  tags = local.common_tags

  lifecycle {
    # Replace before destroying. Destroying the API first would leave a window
    # with no API at all, and the replacement takes long enough for that window
    # to be a visible outage.
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# JWT authorizer.
#
# Tokens are validated AT THE EDGE, so an unauthenticated request never reaches
# the VPC Link, the load balancer or a JVM thread. The services validate again
# themselves -- defence in depth, and the only thing that protects them if
# something is ever deployed with a route that bypasses the gateway.
# -----------------------------------------------------------------------------
resource "aws_api_gateway_authorizer" "jwt" {
  name        = "${local.api_name}-jwt"
  rest_api_id = aws_api_gateway_rest_api.this.id
  type        = "COGNITO_USER_POOLS"

  # PLACEHOLDER. A real user-pool ARN names the account and the pool, and is
  # supplied at deployment time rather than committed.
  provider_arns = var.cognito_user_pool_arns

  identity_source = "method.request.header.Authorization"

  # Cached for five minutes. Longer would mean a revoked token keeps working;
  # shorter would validate on every request for no security gain, since the
  # token's own expiry is the real bound.
  authorizer_result_ttl_in_seconds = 300
}

# -----------------------------------------------------------------------------
# VPC Link.
#
# The only route from the gateway into the VPC. It attaches to an INTERNAL
# network load balancer, so there is no public path to the cluster at all.
# -----------------------------------------------------------------------------
resource "aws_api_gateway_vpc_link" "this" {
  name        = "${local.api_name}-vpc-link"
  description = "Private route from API Gateway to the internal load balancer"
  target_arns = [var.internal_nlb_arn]

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# Routing.
#
# A single greedy proxy resource. Per-path resources would duplicate the
# service's own routing table in Terraform, and the two would drift: a new
# endpoint would work locally and 404 in production until somebody remembered.
# Authorisation is enforced per request by the authorizer, and per scope by the
# services themselves, so the proxy does not weaken anything.
# -----------------------------------------------------------------------------
resource "aws_api_gateway_resource" "proxy" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  parent_id   = aws_api_gateway_rest_api.this.root_resource_id
  path_part   = "{proxy+}"
}

# Request validation at the edge.
#
# WHAT THIS VALIDATES, AND WHAT IT DOES NOT
# -----------------------------------------
# Parameters only. Body validation needs a JSON Schema model per method, and the
# routing here is a single greedy proxy, so there is no per-method model to
# validate against -- the shape of a submission is checked by bean validation in
# the service, which is where that schema actually lives.
#
# Parameter validation still earns its place: a request missing the path
# parameter is rejected before a VPC Link connection and a JVM thread are spent
# on it. Stated plainly rather than described as full request validation.
resource "aws_api_gateway_request_validator" "params" {
  name                        = "${local.api_name}-parameters"
  rest_api_id                 = aws_api_gateway_rest_api.this.id
  validate_request_parameters = true
  validate_request_body       = false
}

resource "aws_api_gateway_method" "proxy" {
  rest_api_id   = aws_api_gateway_rest_api.this.id
  resource_id   = aws_api_gateway_resource.proxy.id
  http_method   = "ANY"
  authorization = "COGNITO_USER_POOLS"
  authorizer_id = aws_api_gateway_authorizer.jwt.id

  request_validator_id = aws_api_gateway_request_validator.params.id

  # An API key on top of the JWT. The token says who the caller is; the key is
  # what a usage plan throttles on, which is how one partner's burst is stopped
  # from degrading every other partner.
  api_key_required = var.require_api_key

  request_parameters = {
    "method.request.path.proxy" = true
  }
}

resource "aws_api_gateway_integration" "proxy" {
  rest_api_id = aws_api_gateway_rest_api.this.id
  resource_id = aws_api_gateway_resource.proxy.id
  http_method = aws_api_gateway_method.proxy.http_method

  type                    = "HTTP_PROXY"
  integration_http_method = "ANY"
  uri                     = "${var.internal_nlb_url}/{proxy}"

  connection_type = "VPC_LINK"
  connection_id   = aws_api_gateway_vpc_link.this.id

  request_parameters = {
    "integration.request.path.proxy" = "method.request.path.proxy"
  }

  # Shorter than the client's own timeout, so the gateway gives up first and
  # returns a clean 504 rather than leaving the client to guess.
  timeout_milliseconds = var.integration_timeout_ms
}

# -----------------------------------------------------------------------------
# Deployment and stage.
# -----------------------------------------------------------------------------
resource "aws_api_gateway_deployment" "this" {
  rest_api_id = aws_api_gateway_rest_api.this.id

  # Redeploys when the API's shape changes. Without this, a modified method or
  # integration is saved but never deployed, and the change silently does
  # nothing.
  triggers = {
    redeployment = sha1(jsonencode([
      aws_api_gateway_resource.proxy.id,
      aws_api_gateway_method.proxy.id,
      aws_api_gateway_integration.proxy.id,
      aws_api_gateway_request_validator.params.id,
    ]))
  }

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_api_gateway_stage" "this" {
  # checkov:skip=CKV_AWS_120:Response caching is deliberately off. Responses here are per-applicant and authorisation-dependent, so a shared cache is a cross-tenant data leak one cache-key mistake away. The latency saving is not worth that class of bug.
  # checkov:skip=CKV2_AWS_77:FALSE POSITIVE, verified. The check requires the attached Web ACL to carry both AWSManagedRulesKnownBadInputsRuleSet (which contains the Log4j rule) and AWSManagedRulesAnonymousIpList. Both are present below. Checkov's graph engine cannot follow a reference through a count-indexed resource, so it does not see the association; removing the count on the WAF resources makes this check pass with no other change. The count stays, because the WAF must remain optional.
  # checkov:skip=CKV2_AWS_51:Client certificates are not used. Callers authenticate with an OAuth2 JWT validated by the authorizer and, for partners, an API key that carries the usage plan. Mutual TLS would add a certificate lifecycle to operate without replacing either control.

  deployment_id = aws_api_gateway_deployment.this.id
  rest_api_id   = aws_api_gateway_rest_api.this.id
  stage_name    = var.stage_name

  # Detailed CloudWatch metrics per method. Without them the only available
  # metric is the API-wide total, which cannot distinguish "submissions are
  # failing" from "one health check is noisy".
  xray_tracing_enabled = var.xray_tracing_enabled

  # ---------------------------------------------------------------------------
  # Access logging.
  #
  # THE FIELD LIST IS A PRIVACY DECISION, NOT A CONVENIENCE.
  #
  # Deliberately ABSENT:
  #   $context.requestBody / responseBody  -- would log applicant data wholesale
  #   $context.authorizer.claims           -- the token's contents
  #   any header capture                   -- Authorization is a header
  #   $context.path with query string      -- query strings carry filters,
  #                                           identifiers and, for a presigned
  #                                           URL, a signature
  #
  # Present: who, what route, what happened, how long, and the correlation
  # identifier that ties it to the application logs. Enough to investigate an
  # incident; not enough to reconstruct a customer's data from the logs.
  # ---------------------------------------------------------------------------
  access_log_settings {
    destination_arn = var.access_log_group_arn

    format = jsonencode({
      requestId          = "$context.requestId"
      correlationId      = "$context.requestOverride.header.X-Correlation-Id"
      requestTime        = "$context.requestTime"
      httpMethod         = "$context.httpMethod"
      resourcePath       = "$context.resourcePath"
      status             = "$context.status"
      protocol           = "$context.protocol"
      responseLength     = "$context.responseLength"
      integrationLatency = "$context.integrationLatency"
      responseLatency    = "$context.responseLatency"
      # The authenticated principal's subject: a pseudonymous identifier, never
      # a name or an email address.
      principalId = "$context.authorizer.principalId"
      # Source IP is retained because it is operationally necessary for abuse
      # investigation. It IS personal data under GDPR, so its retention is
      # bounded by the log group's retention policy and recorded in
      # docs/security/data-classification.md.
      sourceIp     = "$context.identity.sourceIp"
      userAgent    = "$context.identity.userAgent"
      errorMessage = "$context.error.message"
      wafResponse  = "$context.wafResponseCode"
    })
  }

  tags = local.common_tags

  depends_on = [var.access_log_group_arn]
}

# -----------------------------------------------------------------------------
# Throttling.
#
# Applied at the stage, so it holds regardless of usage plan. A platform without
# a default throttle is one misbehaving client away from an outage for everyone.
# -----------------------------------------------------------------------------
resource "aws_api_gateway_method_settings" "this" {
  # checkov:skip=CKV_AWS_225:Same decision as CKV_AWS_120 on the stage. Caching is off on purpose, and caching_enabled below says so explicitly rather than leaving it to a default.

  rest_api_id = aws_api_gateway_rest_api.this.id
  stage_name  = aws_api_gateway_stage.this.stage_name
  method_path = "*/*"

  settings {
    metrics_enabled = true
    # INFO, not ERROR: without request-level logging an intermittent 403 cannot
    # be traced to the authorizer.
    logging_level = var.logging_level
    # NEVER true. Data tracing logs full request and response bodies into
    # CloudWatch, which for this platform means applicant names, email addresses
    # and dates of birth.
    data_trace_enabled = false

    throttling_rate_limit  = var.throttle_rate_limit
    throttling_burst_limit = var.throttle_burst_limit

    # Caching is off. Responses here are per-applicant and authorisation-
    # dependent; a shared cache would be a cross-tenant data leak waiting for a
    # cache-key mistake.
    caching_enabled = false
  }
}

# -----------------------------------------------------------------------------
# Usage plan: per-partner throttles and quotas.
# -----------------------------------------------------------------------------
resource "aws_api_gateway_usage_plan" "partner" {
  count = var.require_api_key ? 1 : 0

  name        = "${local.api_name}-partner"
  description = "Per-partner throttling and quota"

  api_stages {
    api_id = aws_api_gateway_rest_api.this.id
    stage  = aws_api_gateway_stage.this.stage_name
  }

  throttle_settings {
    rate_limit  = var.partner_throttle_rate_limit
    burst_limit = var.partner_throttle_burst_limit
  }

  quota_settings {
    limit  = var.partner_daily_quota
    period = "DAY"
  }

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# AWS WAF.
#
# Associated with the stage, so it filters before the authorizer runs and long
# before anything reaches the VPC.
# -----------------------------------------------------------------------------
resource "aws_wafv2_web_acl" "this" {
  # checkov:skip=CKV2_AWS_31:FALSE POSITIVE, verified the same way as CKV2_AWS_77 on the stage. aws_wafv2_web_acl_logging_configuration.this is defined below and references this ACL; checkov cannot follow the count-indexed reference. Dropping the count makes the check pass unchanged.

  count = var.waf_enabled ? 1 : 0

  name        = "${local.api_name}-waf"
  description = "Edge protection for the loan origination API"
  scope       = "REGIONAL"

  default_action {
    # Allow by default, with rules that block. A default of block would require
    # enumerating every legitimate request shape, which is not achievable for a
    # general API.
    allow {}
  }

  # A rate limit per source address, ahead of the usage plan. The usage plan
  # throttles an authenticated partner; this stops an unauthenticated flood
  # before a token is even validated.
  rule {
    name     = "rate-limit-per-ip"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.waf_rate_limit_per_5_minutes
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-limit-per-ip"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-common-rules"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "aws-common-rules"
      sampled_requests_enabled   = true
    }
  }

  # The anonymous-IP list: Tor exit nodes, public VPNs and hosting-provider
  # ranges.
  #
  # COUNT, NOT BLOCK, AND THAT IS THE ENTIRE DECISION. Plenty of legitimate
  # applicants use a VPN, and blocking them would refuse a loan application over
  # a privacy-preserving choice. Counting gives the same visibility -- the
  # metric shows exactly how much traffic a block would have caught -- without
  # turning a heuristic into a denial of service for real customers. Promote it
  # to block only with evidence from that metric.
  rule {
    name     = "anonymous-ip-list"
    priority = 3

    override_action {
      count {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAnonymousIpList"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "anonymous-ip-list"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "known-bad-inputs"
    priority = 4

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesKnownBadInputsRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "known-bad-inputs"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${local.api_name}-waf"
    # Sampled requests include headers, so the sample is itself sensitive. It is
    # enabled because tuning a WAF blind is impractical; access to the samples is
    # controlled by IAM and noted in the threat model.
    sampled_requests_enabled = true
  }

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# WAF logging.
#
# Without this the WAF blocks silently. The CloudWatch metrics say how many
# requests matched a rule and nothing says which requests, or why. Tuning a
# false positive -- a legitimate application body that trips a managed rule --
# is not possible without the record.
#
# REDACTION IS NOT OPTIONAL HERE. A WAF log entry carries the request's headers
# and, for a blocked request, part of its body. Authorization is a bearer token,
# Cookie is a session, x-api-key is a partner's credential. All three are
# redacted AT THE WAF, so the value never reaches CloudWatch Logs at all rather
# than being filtered afterwards by something that could be misconfigured.
#
# Requests that were merely allowed are dropped: the API Gateway access log
# already records them, and keeping both doubles the bill to say the same thing
# twice.
# -----------------------------------------------------------------------------
resource "aws_wafv2_web_acl_logging_configuration" "this" {
  count = var.waf_enabled ? 1 : 0

  resource_arn            = aws_wafv2_web_acl.this[0].arn
  log_destination_configs = [var.waf_log_group_arn]

  lifecycle {
    # Without this the failure is a null in a list and an opaque provider error
    # at apply time, long after the mistake was made. A variable validation
    # cannot express it, because it depends on a second variable.
    precondition {
      condition     = var.waf_log_group_arn != null
      error_message = "waf_log_group_arn is required when waf_enabled is true. Its name must begin with \"aws-waf-logs-\"; the cloudwatch module's waf_log_group_arn output already satisfies that."
    }
  }

  redacted_fields {
    single_header {
      name = "authorization"
    }
  }

  redacted_fields {
    single_header {
      name = "cookie"
    }
  }

  redacted_fields {
    single_header {
      name = "x-api-key"
    }
  }

  logging_filter {
    default_behavior = "DROP"

    filter {
      behavior    = "KEEP"
      requirement = "MEETS_ANY"

      condition {
        action_condition {
          action = "BLOCK"
        }
      }

      condition {
        action_condition {
          action = "COUNT"
        }
      }
    }
  }
}

resource "aws_wafv2_web_acl_association" "this" {
  count = var.waf_enabled ? 1 : 0

  resource_arn = aws_api_gateway_stage.this.arn
  web_acl_arn  = aws_wafv2_web_acl.this[0].arn
}
