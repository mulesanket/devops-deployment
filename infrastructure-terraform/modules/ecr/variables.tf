variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Environment (development, production)"
  type        = string
}

variable "repository_names" {
  description = "List of ECR repository names to create"
  type        = list(string)
  default     = ["auth-service", "product-service", "cart-service", "order-service"]
}

variable "image_tag_mutability" {
  description = "Tag mutability setting (MUTABLE or IMMUTABLE)"
  type        = string
  default     = "MUTABLE"
}

variable "max_image_count" {
  description = "Max number of images to keep per repository"
  type        = number
  default     = 10
}

variable "force_delete" {
  description = "Force delete repository even if it contains images"
  type        = bool
  default     = true
}
