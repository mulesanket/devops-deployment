
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

output "secrets_manager_arns" {
  description = "ARNs of AWS Secrets Manager secrets per service. Used by ESO IAM policy in Step 2."
  value = {
    auth_service    = module.auth_service_secret.arn
    product_service = module.product_service_secret.arn
    cart_service    = module.cart_service_secret.arn
    order_service   = module.order_service_secret.arn
  }
}

output "app_secrets_kms_key_arn" {
  description = "KMS CMK used to encrypt application secrets. ESO IAM role needs kms:Decrypt on this key."
  value       = module.app_secrets_kms.key_arn
}