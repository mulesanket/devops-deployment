variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Environment (development, production)"
  type        = string
}

variable "queue_name" {
  description = "Name of the SQS queue"
  type        = string
  default     = "signup-email-queue"
}

variable "sns_topic_arn" {
  description = "ARN of the SNS topic to subscribe to"
  type        = string
}
