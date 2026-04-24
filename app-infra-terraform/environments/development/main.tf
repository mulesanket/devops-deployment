# #################################################
# # Call Modules here for Development Environment
# #################################################

# module "s3_frontend"
module "s3_frontend" {
  source = "../../modules/s3-frontend"

  s3_frontend_bucket_name = "shopease-frontend-dev-483829975256"
  environment             = var.environment
}

# module "cloudfront" 
module "cloudfront" {
  source = "../../modules/cloudfront"

  project_name                   = var.project_name
  environment                    = var.environment
  s3_bucket_id                   = module.s3_frontend.frontend_bucket_id
  s3_bucket_regional_domain_name = module.s3_frontend.bucket_regional_domain_name
}

# module "policies": S3 - Cloudfront Bucket Policy Attachment
module "s3_cloudfront_policy" {
  source                      = "../../modules/policies"
  s3_bucket_id                = module.s3_frontend.frontend_bucket_id
  cloudfront_distribution_arn = module.cloudfront.cloudfront_distribution_arn
  s3_bucket_arn               = module.s3_frontend.frontend_bucket_arn
  account_id                  = "483829975256"
}

# module "VPC & it's Components"
module "vpc" {
  source               = "../../modules/vpc"
  project_name         = var.project_name
  eks_cluster_name     = var.project_name
  vpc_cidr             = "10.0.0.0/16"
  availability_zone    = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  private_subnet_cidrs = ["10.0.10.0/24", "10.0.20.0/24", "10.0.30.0/24"]
}

# module "EKS IAM ROLES"
module "eks_iam_roles" {
  source           = "../../modules/eks-iam-role"
  eks_cluster_name = var.eks_cluster_name

}

# module "EKS" 
module "eks" {
  source               = "../../modules/eks"
  eks_cluster_name     = var.eks_cluster_name
  eks_version          = var.eks_version
  eks_cluster_role_arn = module.eks_iam_roles.eks_cluster_role_arn
  private_subnet_ids   = module.vpc.private_subnet_ids
  eks_node_role_arn    = module.eks_iam_roles.eks_cluster_node_role_arn
  worker_instance_type = "t3.medium"

  # ensure cluster waits for VPC + IAM
  depends_on = [module.vpc, module.eks_iam_roles]
}