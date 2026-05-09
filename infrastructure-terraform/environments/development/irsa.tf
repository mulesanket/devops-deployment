locals {
  k8s_namespace = "shopease-webapp-development"
}

resource "aws_iam_policy" "auth_service_sns_publish" {
  name        = "${var.project_name}-${var.environment}-auth-service-sns-publish"
  description = "Allow auth-service to publish signup events to the signup SNS topic."

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "sns:Publish"
        Resource = module.signup_sns.topic_arn
      }
    ]
  })
}

module "auth_service_irsa" {
  source = "../../modules/irsa"

  role_name            = "${var.project_name}-${var.environment}-auth-service-irsa"
  oidc_provider_arn    = module.eks.oidc_provider_arn
  oidc_provider_url    = module.eks.oidc_provider_url
  namespace            = local.k8s_namespace
  service_account_name = "auth-service-sa"
  policy_arns          = [aws_iam_policy.auth_service_sns_publish.arn]

  tags = {
    Service     = "auth-service"
    Environment = var.environment
  }

  depends_on = [module.eks, module.signup_sns]
}
