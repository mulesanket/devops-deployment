variable "aws_region" {
  description = "AWS region"
  type        = string
  default     = "ap-south-1"
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "development"
}

variable "project_name" {
  description = "Project name"
  type        = string
  default     = "shopease-webapp"
}

variable "eks_cluster_name" {
  description = "EKS cluster name"
  type        = string
  default     = "shopease-webapp"
}

variable "eks_version" {
  description = "EKS cluster version"
  type        = string
}

variable "worker_instance_type" {
  description = "EKS worker nodes instance type"
  type        = string
}

variable "db_master_password" {
  description = "Master password for Aurora PostgreSQL"
  type        = string
  sensitive   = true
}

variable "ses_sender_email" {
  description = "Verified SES sender email for welcome emails"
  type        = string
}