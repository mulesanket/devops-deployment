module "s3_frontend" {
  source = "../../modules/s3-frontend"

  s3_frontend_bucket_name = "shopease-frontend-dev-483829975256"
  environment             = var.environment
}

module "cloudfront" {
  source = "../../modules/cloudfront"

  project_name                   = var.project_name
  environment                    = var.environment
  s3_bucket_id                   = module.s3_frontend.frontend_bucket_id
  s3_bucket_regional_domain_name = module.s3_frontend.bucket_regional_domain_name
}

module "s3_cloudfront_policy" {
  source                      = "../../modules/policies"
  s3_bucket_id                = module.s3_frontend.frontend_bucket_id
  cloudfront_distribution_arn = module.cloudfront.cloudfront_distribution_arn
  s3_bucket_arn               = module.s3_frontend.frontend_bucket_arn
  account_id                  = "483829975256"
  sns_topic_arn               = module.signup_sns.topic_arn
  sqs_queue_arn               = module.signup_sqs.queue_arn
  sqs_queue_url               = module.signup_sqs.queue_url
}
