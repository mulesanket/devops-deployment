variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Environment (development, production)"
  type        = string
}

variable "vpc_id" {
  description = "VPC ID where Aurora will be deployed"
  type        = string
}

variable "private_subnet_ids" {
  description = "Private subnet IDs for DB subnet group"
  type        = list(string)
}

variable "eks_node_security_group_ids" {
  description = "Security group IDs of EKS worker nodes (to allow DB access)"
  type        = list(string)
}

variable "engine_version" {
  description = "Aurora PostgreSQL engine version"
  type        = string
  default     = "16.6"
}

variable "database_name" {
  description = "Name of the default database"
  type        = string
  default     = "shopease"
}

variable "master_username" {
  description = "Master username for the DB"
  type        = string
  default     = "shopease_admin"
}

variable "master_password" {
  description = "Master password for the DB"
  type        = string
  sensitive   = true
}

variable "serverless_min_capacity" {
  description = "Minimum ACU for Serverless v2 (0.5 = cheapest)"
  type        = number
  default     = 0.5
}

variable "serverless_max_capacity" {
  description = "Maximum ACU for Serverless v2"
  type        = number
  default     = 4
}

variable "instance_count" {
  description = "Number of Aurora instances (1 for dev, 2+ for prod)"
  type        = number
  default     = 1
}

variable "deletion_protection" {
  description = "Enable deletion protection"
  type        = bool
  default     = false
}
