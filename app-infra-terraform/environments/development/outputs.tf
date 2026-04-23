
output "frontend_bucket_id" {
  value = module.s3_frontend.frontend_bucket_id
}

output "frontend_distribution_id" {
  value = module.cloudfront.cloudfront_distribution_id
}

output "cloudfront_domain_name" {
  value = "https://${module.cloudfront.cloudfront_domain_name}"
}