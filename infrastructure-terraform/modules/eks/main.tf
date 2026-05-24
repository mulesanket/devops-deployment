########################################
# EKS Module
########################################

# --- Control Plane ---
resource "aws_eks_cluster" "eks_control_plane" {
  name     = var.eks_cluster_name
  version  = var.eks_version
  role_arn = var.eks_cluster_role_arn

  vpc_config {
    subnet_ids              = var.private_subnet_ids
    endpoint_public_access  = true
    endpoint_private_access = false
    public_access_cidrs     = var.cluster_api_cidrs
  }
  # Enable EKS Access Entries (in addition to the legacy aws-auth ConfigMap)
  # so we can declaratively map IAM roles (e.g. the Jenkins CI/CD IRSA role)
  # to Kubernetes groups from Terraform. Without this, the cluster defaults to
  # CONFIG_MAP-only and `aws_eks_access_entry` resources cannot be created.
  # API_AND_CONFIG_MAP is the safe migration mode: existing aws-auth entries
  # keep working, new entries can be added via the EKS API.
  #
  # CRITICAL: `bootstrap_cluster_creator_admin_permissions` is set ONCE at
  # cluster creation and CANNOT change without REPLACING the cluster (which
  # rotates the OIDC issuer URL and breaks every IRSA binding in the account).
  # The existing cluster was created with it = true (the EKS default), so we
  # MUST mirror that here. Treat this value as immutable.
  access_config {
    authentication_mode                         = "API_AND_CONFIG_MAP"
    bootstrap_cluster_creator_admin_permissions = true
  }

  tags = {
    Name = "${var.eks_cluster_name}-cluster"
  }
}

# --- AWS VPC CNI Add-on ---
resource "aws_eks_addon" "vpc_cni" {
  cluster_name = aws_eks_cluster.eks_control_plane.name
  addon_name   = "vpc-cni"

  # Make sure control plane is ready first
  depends_on = [aws_eks_cluster.eks_control_plane]
}

# --- Node Group ---
resource "aws_eks_node_group" "worker_node_group" {
  cluster_name    = aws_eks_cluster.eks_control_plane.name
  node_group_name = "${var.eks_cluster_name}-ng"
  node_role_arn   = var.eks_node_role_arn
  subnet_ids      = var.private_subnet_ids

  version = var.eks_version

  scaling_config {
    min_size     = 3
    desired_size = 3
    max_size     = 6
  }

  instance_types = [var.worker_instance_type]
  capacity_type  = "ON_DEMAND"
  ami_type       = "AL2023_x86_64_STANDARD"

  update_config { max_unavailable = 1 }

  # ensure node group waits for control plane
  depends_on = [aws_eks_cluster.eks_control_plane, aws_eks_addon.vpc_cni]
}
