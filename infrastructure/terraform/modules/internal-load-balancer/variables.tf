variable "environment" {
  description = "Environment name, prefixed to the load balancer name."
  type        = string
}

variable "vpc_id" {
  description = "VPC the load balancer runs in."
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR, used as the egress destination for the load balancer's own traffic to its targets."
  type        = string
}

variable "private_subnet_ids" {
  description = <<-EOT
    Private subnets, one per Availability Zone.

    Private only. There is no variable that would place this load balancer in a
    public subnet, and `internal` is hard-coded true: an internet-facing load
    balancer here bypasses WAF, the JWT authorizer, throttling, usage plans and
    access logging, silently, while everything still appears to work.
  EOT
  type        = list(string)

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "At least two Availability Zones are required; three is strongly preferred."
  }
}

# -----------------------------------------------------------------------------
# Who may reach it
# -----------------------------------------------------------------------------
variable "allowed_source_cidrs" {
  description = <<-EOT
    Source ranges permitted to reach the listener.

    The VPC CIDR, because the callers are API Gateway's VPC Link network
    interfaces, and AWS does not expose a security group for those -- so this is
    the one place in this platform where a CIDR rule is unavoidable rather than
    lazy. It is narrowed to the VPC and to the listener port.

    Never 0.0.0.0/0: the load balancer has no public address, so a rule allowing
    the internet would grant nothing and would only mislead the next reader.
  EOT
  type        = list(string)

  validation {
    condition     = !contains(var.allowed_source_cidrs, "0.0.0.0/0")
    error_message = "0.0.0.0/0 is meaningless on an internal load balancer and misleading to leave in a rule. Name the VPC's range."
  }
}

variable "listener_port" {
  description = "Port the load balancer listens on. The VPC Link connects here."
  type        = number
  default     = 443
}

# -----------------------------------------------------------------------------
# TLS
# -----------------------------------------------------------------------------
variable "enable_tls" {
  description = <<-EOT
    Terminate TLS at the load balancer.

    True, for two reasons. The hop is inside the VPC, which is precisely the
    argument that normalises unencrypted internal traffic -- and, less obviously,
    **an NLB writes access logs only for TLS listeners**. With a plain TCP
    listener the access-log setting is accepted, the bucket stays empty, and
    nobody notices until an investigation needs the logs.
  EOT
  type        = bool
  default     = true
}

variable "certificate_arn" {
  description = <<-EOT
    ACM certificate for the TLS listener. Required when enable_tls is true.

    PLACEHOLDER by default: null. A real ARN names the account and the
    certificate, neither of which belongs in this repository.
  EOT
  type        = string
  default     = null
}

variable "ssl_policy" {
  description = <<-EOT
    TLS negotiation policy.

    TLS 1.3 and 1.2 only. The policies that also accept 1.0 and 1.1 exist for
    old clients, and there are none here: the only caller is API Gateway.
  EOT
  type        = string
  default     = "ELBSecurityPolicy-TLS13-1-2-2021-06"
}

# -----------------------------------------------------------------------------
# Targets
# -----------------------------------------------------------------------------
variable "target_type" {
  description = <<-EOT
    What the target group contains.

    `alb` by default: the NLB forwards to the internal ALB the AWS Load Balancer
    Controller creates from the chart's Ingress, which is what makes path-based
    routing possible behind a layer-4 load balancer.

    `ip` is for a deployment that runs an ingress controller inside the cluster
    instead; the pods are then registered by a TargetGroupBinding referencing
    this group's ARN, which is why that ARN is an output.
  EOT
  type        = string
  default     = "alb"

  validation {
    condition     = contains(["alb", "ip"], var.target_type)
    error_message = "target_type must be \"alb\" (forward to the controller-managed ALB) or \"ip\" (register pods directly)."
  }
}

variable "target_arn" {
  description = <<-EOT
    The ALB to forward to, or null.

    NULL ON A FIRST APPLY, and that is expected rather than an omission. The ALB
    is created by the Load Balancer Controller from the chart's Ingress, which
    needs a cluster, which needs this module's outputs -- so its ARN cannot exist
    yet. The load balancer and target group are created empty, and the ARN is
    supplied on a second apply once the chart is installed.
  EOT
  type        = string
  default     = null
}

variable "target_port" {
  description = <<-EOT
    Port on the target.

    80: TLS terminates at this load balancer, and the hop to the ALB is inside
    the VPC. The chart's Ingress listens on 80 for the same reason and documents
    it rather than assuming it.
  EOT
  type        = number
  default     = 80
}

variable "health_check_path" {
  description = <<-EOT
    Health check path.

    The READINESS endpoint, not liveness and not "/". Readiness includes the
    database, so the load balancer's view of health matches Kubernetes'. Liveness
    would send traffic to a pod that is running but cannot serve; "/" would report
    healthy for a service whose dependencies are all down.
  EOT
  type        = string
  default     = "/actuator/health/readiness"
}

variable "deregistration_delay_seconds" {
  description = <<-EOT
    How long a removed target keeps draining.

    Must be SHORTER than the pod's terminationGracePeriodSeconds, or the pod is
    killed while the load balancer still believes it is draining, and in-flight
    requests are dropped during every ordinary deployment.
  EOT
  type        = number
  default     = 30
}

# -----------------------------------------------------------------------------
# Behaviour and cost
# -----------------------------------------------------------------------------
variable "enable_cross_zone_load_balancing" {
  description = <<-EOT
    Send traffic to targets in other Availability Zones.

    COST: billed for every byte crossing a zone boundary, and off by default on
    an NLB.

    On here because the alternative is worse: with it off, each zone's load
    balancer node sends only to targets in its own zone, so a zone holding one
    pod receives the same share of traffic as a zone holding four. The chart's
    topology spread constraints even out the pods, not the traffic.
  EOT
  type        = bool
  default     = true
}

variable "enable_deletion_protection" {
  description = "Refuse `terraform destroy` until deliberately turned off. The last line of defence against a destroy meant for another workspace."
  type        = bool
  default     = true
}

variable "access_logs_bucket" {
  description = <<-EOT
    Bucket receiving NLB access logs. Null disables them.

    Remember that an NLB writes access logs for TLS listeners ONLY. With
    enable_tls false this setting is accepted and produces nothing.
  EOT
  type        = string
  default     = null
}

variable "tags" {
  description = "Tags applied to every resource in the module."
  type        = map(string)
  default     = {}
}
