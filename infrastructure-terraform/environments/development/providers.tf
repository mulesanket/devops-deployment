
terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    helm = {
      source  = "hashicorp/helm"
      version = "~> 3.0"
    }
    kubernetes = {
      source  = "hashicorp/kubernetes"
      version = "~> 2.30"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

# ----------------------------------------------------------------------------
# Kubernetes + Helm providers
# ----------------------------------------------------------------------------
# Both providers authenticate to the EKS cluster the same way `kubectl` does:
# they invoke `aws eks get-token` on every API call, using whichever AWS
# credentials Terraform is already running with. This avoids storing static
# kubeconfig files on disk and keeps auth tied to your IAM identity.
# ----------------------------------------------------------------------------

provider "kubernetes" {
  host                   = module.eks.eks_cluster_endpoint
  cluster_ca_certificate = base64decode(module.eks.eks_cluster_ca)

  exec {
    api_version = "client.authentication.k8s.io/v1beta1"
    command     = "aws"
    args        = ["eks", "get-token", "--cluster-name", module.eks.eks_cluster_name, "--region", var.aws_region, "--output", "json"]
  }
}

provider "helm" {
  kubernetes = {
    host                   = module.eks.eks_cluster_endpoint
    cluster_ca_certificate = base64decode(module.eks.eks_cluster_ca)

    exec = {
      api_version = "client.authentication.k8s.io/v1beta1"
      command     = "aws"
      args        = ["eks", "get-token", "--cluster-name", module.eks.eks_cluster_name, "--region", var.aws_region, "--output", "json"]
    }
  }
}
