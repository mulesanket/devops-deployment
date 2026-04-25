variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Environment (development, production)"
  type        = string
}

variable "topic_name" {
  description = "Name of the SNS topic"
  type        = string
  default     = "signup-topic"
}
