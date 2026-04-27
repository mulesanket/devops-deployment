variable "project_name" {
  description = "Project name for resource naming"
  type        = string
}

variable "environment" {
  description = "Environment (development, production)"
  type        = string
}

variable "lambda_source_path" {
  description = "Path to the Lambda source file (welcome_email.py)"
  type        = string
}

variable "sqs_queue_arn" {
  description = "ARN of the SQS queue that triggers this Lambda"
  type        = string
}

variable "sender_email" {
  description = "Verified SES sender email address"
  type        = string
}
