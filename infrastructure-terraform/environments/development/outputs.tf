
output "frontend_bucket_id" {
  value = module.s3_frontend.frontend_bucket_id
}

output "frontend_distribution_id" {
  value = module.cloudfront.cloudfront_distribution_id
}

output "cloudfront_domain_name" {
  value = "https://${module.cloudfront.cloudfront_domain_name}"
}

# IRSA role ARNs - paste into K8s ServiceAccount annotations
output "auth_service_irsa_role_arn" {
  description = "IAM role ARN for auth-service. Annotate the K8s SA with eks.amazonaws.com/role-arn=<this>"
  value       = module.auth_service_irsa.role_arn
}