
variable "cloudfront_distribution_arn" {
  description = "ARN of the CloudFront distribution"
  type        = string

}

variable "s3_bucket_id" {
  description = "S3 frontend bucket ID"
  type        = string
}

variable "s3_bucket_arn" {
  description = "ARN of the S3 bucket"
  type        = string
}

variable "account_id" {
  description = "AWS Account ID"
  type        = string
}

variable "sns_topic_arn" {
  description = "ARN of the SNS signup topic"
  type        = string
}

variable "sqs_queue_arn" {
  description = "ARN of the SQS signup queue"
  type        = string
}

variable "sqs_queue_url" {
  description = "URL of the SQS signup queue"
  type        = string
}