module "eks_iam_roles" {
  source           = "../../modules/eks-iam-role"
  eks_cluster_name = var.eks_cluster_name
}

module "eks" {
  source               = "../../modules/eks"
  eks_cluster_name     = var.eks_cluster_name
  eks_version          = var.eks_version
  eks_cluster_role_arn = module.eks_iam_roles.eks_cluster_role_arn
  private_subnet_ids   = module.vpc.private_subnet_ids
  eks_node_role_arn    = module.eks_iam_roles.eks_cluster_node_role_arn
  worker_instance_type = var.worker_instance_type

  depends_on = [module.vpc, module.eks_iam_roles]
}
