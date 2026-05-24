module "rds" {
  source = "../../modules/rds"

  project_name                = var.project_name
  environment                 = var.environment
  vpc_id                      = module.vpc.vpc_id
  private_subnet_ids          = module.vpc.private_subnet_ids
  eks_node_security_group_ids = [module.eks.eks_cluster_security_group_id]
  database_name               = "shopease"
  master_username             = "shopease_admin"
  master_password             = var.db_master_password
  engine_version              = "17.7"
  serverless_min_capacity     = 1
  serverless_max_capacity     = 2
  instance_count              = 1
  deletion_protection         = false

  depends_on = [module.vpc, module.eks]
}
