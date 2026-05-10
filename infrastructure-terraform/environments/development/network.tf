module "vpc" {
  source               = "../../modules/vpc"
  project_name         = var.project_name
  eks_cluster_name     = var.eks_cluster_name
  vpc_cidr             = "10.0.0.0/16"
  availability_zone    = ["ap-south-1a", "ap-south-1b", "ap-south-1c"]
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/24"]
  private_subnet_cidrs = ["10.0.10.0/24", "10.0.20.0/24", "10.0.30.0/24"]
}
