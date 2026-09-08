# =============================================================================
# Amazon EKS.
#
# The four services run here. Everything below is shaped by three decisions that
# are cheap now and expensive to retrofit:
#
#   1. THE API SERVER IS PRIVATE. No public endpoint by default. A cluster whose
#      control plane is reachable from the internet depends entirely on its
#      authentication being correct, forever.
#   2. AUTHORISATION USES ACCESS ENTRIES, NOT aws-auth. The ConfigMap is
#      deprecated, and a bad edit to it locks every principal out of a running
#      cluster with no supported recovery. Access entries are declarative, and a
#      mistake is fixed by another apply.
#   3. PODS CANNOT REACH INSTANCE METADATA. The launch template sets the IMDSv2
#      hop limit to 1, so a request from inside a container is dropped before it
#      reaches the node's role. Without it, any compromised pod inherits every
#      permission the node has.
#
# COST WARNING: the control plane is billed hourly at a fixed rate whether or not
# a single pod is running, and the node group is billed per instance-hour on top.
# Neither scales to zero. See docs/operations/cost.md.
#
# NOTHING HERE HAS BEEN APPLIED. No cluster exists.
# =============================================================================

locals {
  cluster_name = "${var.environment}-los"

  common_tags = merge(var.tags, {
    Module = "eks"
  })
}

# -----------------------------------------------------------------------------
# Cluster IAM role.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "cluster_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  name               = "${local.cluster_name}-cluster"
  assume_role_policy = data.aws_iam_policy_document.cluster_assume_role.json
  tags               = local.common_tags
}

resource "aws_iam_role_policy_attachment" "cluster" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:${var.aws_partition}:iam::aws:policy/AmazonEKSClusterPolicy"
}

# -----------------------------------------------------------------------------
# Control plane security group.
#
# Managed here rather than left to EKS so its rules are reviewable in a diff.
# -----------------------------------------------------------------------------
resource "aws_security_group" "cluster" {
  name        = "${local.cluster_name}-control-plane"
  description = "EKS control plane for ${local.cluster_name}"
  vpc_id      = var.vpc_id

  tags = merge(local.common_tags, { Name = "${local.cluster_name}-control-plane" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "cluster_from_nodes" {
  security_group_id            = aws_security_group.cluster.id
  referenced_security_group_id = aws_security_group.nodes.id
  from_port                    = 443
  to_port                      = 443
  ip_protocol                  = "tcp"
  description                  = "Kubelet and pods reaching the API server"

  tags = local.common_tags
}

# Egress to the nodes, which the control plane initiates for `kubectl exec`,
# `kubectl logs` and admission webhooks. Without it those time out in a way that
# looks like an application fault.
#
# NAMED PORTS, NOT 1025-65535. The wide range is the shape most examples use and
# it is a holdover: the control plane needs the kubelet API and whatever ports
# admission webhooks listen on, which is a handful of ports rather than
# sixty-four thousand. The wide range also spans 3389 and 22, which is what a
# scanner notices and a reviewer should.
resource "aws_vpc_security_group_egress_rule" "cluster_to_kubelet" {
  security_group_id            = aws_security_group.cluster.id
  referenced_security_group_id = aws_security_group.nodes.id
  from_port                    = 10250
  to_port                      = 10250
  ip_protocol                  = "tcp"
  description                  = "Control plane to the kubelet API"

  tags = local.common_tags
}

resource "aws_vpc_security_group_egress_rule" "cluster_to_webhooks" {
  for_each = toset([for port in var.admission_webhook_ports : tostring(port)])

  security_group_id            = aws_security_group.cluster.id
  referenced_security_group_id = aws_security_group.nodes.id
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  ip_protocol                  = "tcp"
  description                  = "Control plane to an admission webhook"

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# Node security group.
#
# This is the identity the RDS and MSK modules grant access to, which is why it
# is an output. A CIDR-based grant would follow whatever happens to hold an
# address in that range; a security-group grant follows the workload.
# -----------------------------------------------------------------------------
resource "aws_security_group" "nodes" {
  name        = "${local.cluster_name}-nodes"
  description = "EKS worker nodes for ${local.cluster_name}"
  vpc_id      = var.vpc_id

  tags = merge(local.common_tags, {
    Name = "${local.cluster_name}-nodes"
    # Required by the AWS VPC CNI and the load balancer controller to find the
    # security group they should attach to.
    "kubernetes.io/cluster/${local.cluster_name}" = "owned"
  })

  lifecycle {
    create_before_destroy = true
  }
}

# The mirror of the control plane's egress, and narrow for the same reason.
resource "aws_vpc_security_group_ingress_rule" "nodes_from_cluster_kubelet" {
  security_group_id            = aws_security_group.nodes.id
  referenced_security_group_id = aws_security_group.cluster.id
  from_port                    = 10250
  to_port                      = 10250
  ip_protocol                  = "tcp"
  description                  = "Kubelet API, from the control plane"

  tags = local.common_tags
}

resource "aws_vpc_security_group_ingress_rule" "nodes_from_cluster_webhooks" {
  for_each = toset([for port in var.admission_webhook_ports : tostring(port)])

  security_group_id            = aws_security_group.nodes.id
  referenced_security_group_id = aws_security_group.cluster.id
  from_port                    = tonumber(each.value)
  to_port                      = tonumber(each.value)
  ip_protocol                  = "tcp"
  description                  = "Admission webhook, from the control plane"

  tags = local.common_tags
}

# Pod-to-pod traffic across nodes. Broad at the security-group level and narrowed
# by NetworkPolicies inside the cluster, which is where per-service rules belong:
# a security group cannot express "the document service may reach S3 but not the
# audit service".
resource "aws_vpc_security_group_ingress_rule" "nodes_from_nodes" {
  security_group_id            = aws_security_group.nodes.id
  referenced_security_group_id = aws_security_group.nodes.id
  ip_protocol                  = "-1"
  description                  = "Pod to pod within the cluster"

  tags = local.common_tags
}

resource "aws_vpc_security_group_ingress_rule" "nodes_from_load_balancer" {
  for_each = toset(var.load_balancer_security_group_ids)

  security_group_id            = aws_security_group.nodes.id
  referenced_security_group_id = each.value
  from_port                    = var.node_port_range_start
  to_port                      = var.node_port_range_end
  ip_protocol                  = "tcp"
  description                  = "Internal load balancer to NodePort services"

  tags = local.common_tags
}

# Egress is unrestricted, and that is a deliberate, narrow admission rather than
# laziness. Nodes must reach the EKS API, ECR, S3 and the OIDC issuer, and the
# addresses of those move. The controls that matter are elsewhere and are
# specific: the data tier has no NAT route at all, NetworkPolicies default to
# deny, and IMDS is unreachable from a pod.
resource "aws_vpc_security_group_egress_rule" "nodes_egress" {
  security_group_id = aws_security_group.nodes.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "Node egress to AWS APIs and image registries"

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# The cluster.
# -----------------------------------------------------------------------------
resource "aws_eks_cluster" "this" {
  name     = local.cluster_name
  role_arn = aws_iam_role.cluster.arn
  version  = var.kubernetes_version

  vpc_config {
    subnet_ids         = var.private_subnet_ids
    security_group_ids = [aws_security_group.cluster.id]

    # Private by default. A publicly reachable API server is protected only by
    # its authentication being correct, and it is scanned continuously.
    endpoint_private_access = true
    endpoint_public_access  = var.endpoint_public_access
    # Only consulted when public access is on. The variable's validation refuses
    # 0.0.0.0/0, so enabling public access still cannot open it to everyone.
    public_access_cidrs = var.endpoint_public_access ? var.endpoint_public_access_cidrs : null
  }

  # ---------------------------------------------------------------------------
  # Envelope encryption for Kubernetes Secrets.
  #
  # Without this, a Secret is base64 in etcd -- which is not encryption, and is
  # the reason this platform delivers its secrets as files through the CSI driver
  # instead. This protects what still ends up there, including service account
  # tokens.
  # ---------------------------------------------------------------------------
  encryption_config {
    provider {
      key_arn = var.kms_key_arn
    }
    resources = ["secrets"]
  }

  # ---------------------------------------------------------------------------
  # Control plane logging.
  #
  # ALL FIVE, and `audit` is the one that matters. It is the record of who asked
  # the API server for what, it is the first thing requested in an investigation,
  # and it cannot be reconstructed later if it was not being captured at the
  # time.
  #
  # COST: audit logs are the largest contributor to CloudWatch Logs ingestion on
  # a busy cluster. Retention is set on the log group, not here.
  # ---------------------------------------------------------------------------
  enabled_cluster_log_types = var.enabled_cluster_log_types

  access_config {
    # API, not API_AND_CONFIG_MAP. The aws-auth ConfigMap is deprecated, and a
    # malformed edit to it locks every principal out of a running cluster with no
    # supported recovery path. Access entries are declarative and a mistake is
    # fixed by another apply.
    authentication_mode = "API"

    # False, deliberately. The default silently grants cluster-admin to whichever
    # principal happened to run `terraform apply` -- frequently a CI role, which
    # then holds standing admin nobody granted on purpose. Administrators are
    # named explicitly below, and the variable's validation refuses an empty list
    # so the cluster cannot be created with nobody able to reach it.
    bootstrap_cluster_creator_admin_permissions = false
  }

  # Ensures the role's policy is attached before the cluster tries to use it.
  # Without it, creation intermittently fails on a race that looks like a
  # permissions problem.
  depends_on = [
    aws_iam_role_policy_attachment.cluster,
    aws_cloudwatch_log_group.cluster,
  ]

  tags = merge(local.common_tags, { Name = local.cluster_name })
}

# -----------------------------------------------------------------------------
# The control plane log group.
#
# Created here rather than left to EKS. EKS creates it implicitly with NO
# retention and no customer managed key, so the logs accumulate forever, and the
# audit log is precisely the one that should not sit unencrypted and unbounded.
# -----------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "cluster" {
  name              = "/aws/eks/${local.cluster_name}/cluster"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.log_kms_key_arn

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# IRSA.
#
# Registers the cluster's OIDC issuer with IAM, which is what lets a pod assume
# a role through its service account rather than inheriting the node's. It is the
# difference between "the document service can read the documents bucket" and
# "anything running on this node can".
# -----------------------------------------------------------------------------
data "tls_certificate" "oidc" {
  url = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "this" {
  url             = aws_eks_cluster.this.identity[0].oidc[0].issuer
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.oidc.certificates[0].sha1_fingerprint]

  tags = local.common_tags
}

# -----------------------------------------------------------------------------
# Cluster administrators.
#
# Named roles, never users: a role can be assumed with MFA and its session is
# time-bounded and auditable.
# -----------------------------------------------------------------------------
resource "aws_eks_access_entry" "admin" {
  for_each = toset(var.cluster_admin_role_arns)

  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.value
  type          = "STANDARD"

  tags = local.common_tags
}

resource "aws_eks_access_policy_association" "admin" {
  for_each = toset(var.cluster_admin_role_arns)

  cluster_name  = aws_eks_cluster.this.name
  principal_arn = each.value
  policy_arn    = "arn:${var.aws_partition}:eks::aws:cluster-access-policy/AmazonEKSClusterAdminPolicy"

  access_scope {
    type = "cluster"
  }

  depends_on = [aws_eks_access_entry.admin]
}

# -----------------------------------------------------------------------------
# Node IAM role.
#
# Deliberately minimal. It carries what the kubelet needs to join the cluster and
# pull images, and NOTHING the applications use: every application permission is
# granted per service through IRSA. A node role with application permissions is a
# node role every pod on the node inherits.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "node_assume_role" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "nodes" {
  name               = "${local.cluster_name}-nodes"
  assume_role_policy = data.aws_iam_policy_document.node_assume_role.json
  tags               = local.common_tags
}

resource "aws_iam_role_policy_attachment" "nodes" {
  for_each = toset([
    "AmazonEKSWorkerNodePolicy",
    "AmazonEKS_CNI_Policy",
    # Pull-only. Nodes must never be able to push an image: a compromised node
    # that can write to the registry can replace what every other node runs.
    "AmazonEC2ContainerRegistryReadOnly",
    # SSM Session Manager, so a node can be reached for diagnosis without a
    # bastion, an SSH key or an inbound port.
    "AmazonSSMManagedInstanceCore",
  ])

  role       = aws_iam_role.nodes.name
  policy_arn = "arn:${var.aws_partition}:iam::aws:policy/${each.value}"
}

# -----------------------------------------------------------------------------
# Node launch template.
#
# The reason this module does not simply let the managed node group use its
# default template: the default leaves the IMDSv2 hop limit at 2, and a pod is
# one hop away.
# -----------------------------------------------------------------------------
resource "aws_launch_template" "nodes" {
  name_prefix = "${local.cluster_name}-nodes-"
  description = "EKS nodes for ${local.cluster_name}"

  vpc_security_group_ids = [aws_security_group.nodes.id]

  metadata_options {
    http_endpoint = "enabled"
    # IMDSv2 only. Version 1 answers any process that can make an HTTP request,
    # which is what turns a server-side request forgery into stolen credentials.
    http_tokens = "required"
    # ONE HOP. A container's request crosses the network namespace boundary and
    # is therefore two hops, so this drops it: a compromised pod cannot read the
    # node's role.
    #
    # Anything that genuinely needs instance metadata must run with host
    # networking, deliberately. Nothing in this platform does.
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "disabled"
  }

  block_device_mappings {
    device_name = "/dev/xvda"

    ebs {
      volume_size = var.node_disk_size_gb
      volume_type = "gp3"
      encrypted   = true
      kms_key_id  = var.kms_key_arn
      # Deleted with the instance. A node's root volume holds container layers
      # and whatever a pod wrote to an emptyDir; leaving them behind accumulates
      # both cost and data.
      delete_on_termination = true
    }
  }

  monitoring {
    enabled = var.detailed_monitoring
  }

  tag_specifications {
    resource_type = "instance"
    tags          = merge(local.common_tags, { Name = "${local.cluster_name}-node" })
  }

  tag_specifications {
    resource_type = "volume"
    tags          = local.common_tags
  }

  tags = local.common_tags

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# Managed node group.
#
# Private subnets only. A node with a public address is a node exposed by
# default, and nothing here needs inbound traffic from the internet: the load
# balancer is internal and the API server is private.
# -----------------------------------------------------------------------------
resource "aws_eks_node_group" "this" {
  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${local.cluster_name}-default"
  node_role_arn   = aws_iam_role.nodes.arn
  subnet_ids      = var.private_subnet_ids

  instance_types = var.node_instance_types
  capacity_type  = var.node_capacity_type

  scaling_config {
    desired_size = var.node_desired_size
    min_size     = var.node_min_size
    max_size     = var.node_max_size
  }

  # One node at a time, and never below the minimum. The PodDisruptionBudgets in
  # the Helm chart are what actually protect availability during this; without
  # them a rolling update can evict every replica of a service at once.
  update_config {
    max_unavailable = 1
  }

  launch_template {
    id      = aws_launch_template.nodes.id
    version = aws_launch_template.nodes.latest_version
  }

  labels = var.node_labels

  depends_on = [aws_iam_role_policy_attachment.nodes]

  tags = local.common_tags

  lifecycle {
    # The cluster autoscaler owns the desired size once it is running. Without
    # this, every apply would reset the cluster to its configured size and undo
    # whatever scaling had happened.
    ignore_changes = [scaling_config[0].desired_size]
  }
}

# -----------------------------------------------------------------------------
# Add-ons.
#
# Pinned by version, and configured to preserve on delete so that removing the
# add-on from Terraform does not remove CoreDNS from a running cluster.
#
# The Pod Identity agent is included because it is the successor to IRSA and the
# two coexist; the chart uses IRSA annotations today, and having the agent
# present means moving is a chart change rather than a cluster change.
# -----------------------------------------------------------------------------
resource "aws_eks_addon" "this" {
  for_each = var.addon_versions

  cluster_name  = aws_eks_cluster.this.name
  addon_name    = each.key
  addon_version = each.value

  # OVERWRITE, not NONE: a self-managed CoreDNS installed before the add-on
  # existed would otherwise block every future update, and the failure appears
  # months later as an add-on that silently will not upgrade.
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  # PRESERVE. Deleting the vpc-cni add-on from a running cluster removes pod
  # networking, and there is no version of that which is a good afternoon.
  preserve = true

  tags = local.common_tags

  depends_on = [aws_eks_node_group.this]
}
