data "aws_iam_policy_document" "external_secrets_read" {
  statement {
    sid    = "ReadShopEaseAppSecrets"
    effect = "Allow"
    actions = [
      "secretsmanager:GetSecretValue",
      "secretsmanager:DescribeSecret",
      "secretsmanager:ListSecretVersionIds"
    ]
    resources = [
      "arn:aws:secretsmanager:${var.aws_region}:*:secret:shopease/${var.environment}/*"
    ]
  }

  statement {
    sid    = "DecryptAppSecretsCMK"
    effect = "Allow"
    actions = [
      "kms:Decrypt",
      "kms:DescribeKey"
    ]
    resources = [module.app_secrets_kms.key_arn]
  }
}

resource "aws_iam_policy" "external_secrets_read" {
  name        = "${var.project_name}-${var.environment}-external-secrets-read"
  description = "Allow External Secrets Operator to read application secrets from AWS Secrets Manager."
  policy      = data.aws_iam_policy_document.external_secrets_read.json
}

module "external_secrets_irsa" {
  source = "../../modules/irsa"

  role_name            = "${var.project_name}-${var.environment}-external-secrets-irsa"
  oidc_provider_arn    = module.eks.oidc_provider_arn
  oidc_provider_url    = module.eks.oidc_provider_url
  namespace            = "external-secrets"
  service_account_name = "external-secrets"
  policy_arns          = [aws_iam_policy.external_secrets_read.arn]

  tags = {
    Component   = "external-secrets-operator"
    Environment = var.environment
  }

  depends_on = [module.eks, module.app_secrets_kms]
}

# ----------------------------------------------------------------------------
# External Secrets Operator - Helm release
# ----------------------------------------------------------------------------
# Installs the ESO controller, webhook, cert-controller, CRDs, RBAC, and a
# ServiceAccount named "external-secrets" in the "external-secrets" namespace.
#
# The ServiceAccount is annotated with the IRSA role ARN so the controller
# pod automatically assumes that role when calling AWS Secrets Manager / KMS.
#
# Chart docs: https://external-secrets.io/latest/introduction/getting-started/
# ----------------------------------------------------------------------------
resource "helm_release" "external_secrets" {
  name             = "external-secrets"
  repository       = "https://charts.external-secrets.io"
  chart            = "external-secrets"
  version          = "0.10.4"
  namespace        = "external-secrets"
  create_namespace = true

  # Wait for chart resources to become ready before marking apply successful.
  atomic  = true
  wait    = true
  timeout = 600

  # Install CRDs bundled with the chart, and bind the chart's ServiceAccount
  # to our IRSA role so the controller can call AWS Secrets Manager / KMS.
  # Escaped dots in the annotation key are required by Helm --set syntax
  # (unescaped dots are interpreted as nested-key separators).
  set = [
    {
      name  = "installCRDs"
      value = "true"
    },
    {
      name  = "serviceAccount.annotations.eks\\.amazonaws\\.com/role-arn"
      value = module.external_secrets_irsa.role_arn
    },
  ]

  depends_on = [
    module.eks,
    module.external_secrets_irsa,
  ]
}

