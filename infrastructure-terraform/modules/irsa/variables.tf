########################################
# IRSA Module - Variables
# Reusable module: creates ONE IAM role bound to ONE
# Kubernetes ServiceAccount via the EKS OIDC provider.
########################################

variable "role_name" {
  description = "Name of the IAM role to create (must be unique per AWS account)."
  type        = string
}

variable "oidc_provider_arn" {
  description = "ARN of the EKS cluster's IAM OIDC provider (from EKS module)."
  type        = string
}

variable "oidc_provider_url" {
  description = "URL of the EKS cluster's IAM OIDC provider WITHOUT the https:// prefix (e.g. oidc.eks.ap-south-1.amazonaws.com/id/ABC...)."
  type        = string
}

variable "namespace" {
  description = "Kubernetes namespace where the ServiceAccount lives."
  type        = string
}

variable "service_account_name" {
  description = "Name of the Kubernetes ServiceAccount allowed to assume this role."
  type        = string
}

variable "policy_arns" {
  description = "List of IAM policy ARNs to attach to the role. Pass an empty list for a role with no permissions."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "Tags to apply to the IAM role."
  type        = map(string)
  default     = {}
}
