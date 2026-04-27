
variable "eks_cluster_name" {
  type = string
}

variable "eks_version" {
  type = string
}

variable "cluster_api_cidrs" {
  type    = list(string)
  default = ["0.0.0.0/0"]
}

variable "eks_cluster_role_arn" {
  type = string
}

variable "eks_node_role_arn" {
  type = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "worker_instance_type" {
  type = string
}