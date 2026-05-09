########################################
# IRSA - IAM Roles for Kubernetes ServiceAccounts
#
# Each microservice that needs AWS access gets its own IAM role
# bound to a specific Kubernetes ServiceAccount via the cluster's
# OIDC provider. Pods assume the role via STS:AssumeRoleWithWebIdentity
# at runtime — no static AWS credentials anywhere.
#
# Today only auth-service needs AWS access (SNS:Publish for signup
# events). The other services use bare K8s ServiceAccounts (created
# in deployment-kubernetes/<svc>/service-account.yaml) with no AWS
# permissions — better than the cluster's `default` ServiceAccount.
########################################

locals {
  k8s_namespace = "shopease-webapp-development"
}

# ──────────────────────────────────────────────────────────
# auth-service: needs SNS:Publish on the signup topic
# ──────────────────────────────────────────────────────────

# Least-privilege IAM policy: publish to ONE specific SNS topic, nothing else.
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
