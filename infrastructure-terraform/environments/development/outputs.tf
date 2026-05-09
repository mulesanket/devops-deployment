
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
    auth_service    = aws_secretsmanager_secret.auth_service.arn
    product_service = aws_secretsmanager_secret.product_service.arn
    cart_service    = aws_secretsmanager_secret.cart_service.arn
    order_service   = aws_secretsmanager_secret.order_service.arn
  }
}