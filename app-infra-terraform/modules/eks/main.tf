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

  tags = {
    Name = var.eks_cluster_name
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
