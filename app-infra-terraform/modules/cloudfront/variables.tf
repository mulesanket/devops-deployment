
variable "project_name" {
  description = "Project name used for naming resources"
  type        = string
}

variable "environment" {
  description = "Deployment environment like dev, qa, prod"
  type        = string
}

variable "s3_bucket_id" {
  description = "ID of the S3 bucket used as CloudFront origin"
  type        = string
}

variable "s3_bucket_regional_domain_name" {
  description = "Regional domain name of the S3 bucket"
  type        = string
}

variable "price_class" {
  description = "CloudFront price class"
  type        = string
  default     = "PriceClass_100"
}
