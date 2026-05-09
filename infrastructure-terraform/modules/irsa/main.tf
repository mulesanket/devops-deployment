########################################
# IRSA Module - IAM Role for ServiceAccount
#
# Creates an IAM role whose trust policy allows ONLY a specific
# Kubernetes ServiceAccount (in a specific namespace, in a specific
# EKS cluster) to assume it via STS:AssumeRoleWithWebIdentity.
#
# The :sub condition cryptographically binds this role to one and
# only one pod identity — even a leaked token from another pod or
# namespace cannot assume this role.
########################################

resource "aws_iam_role" "this" {
  name = var.role_name

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Federated = var.oidc_provider_arn
        }
        Action = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = {
            "${var.oidc_provider_url}:sub" = "system:serviceaccount:${var.namespace}:${var.service_account_name}"
            "${var.oidc_provider_url}:aud" = "sts.amazonaws.com"
          }
        }
      }
    ]
  })

  tags = merge(
    var.tags,
    {
      Name      = var.role_name
      ManagedBy = "terraform"
      Component = "irsa"
    }
  )
}

# Attach zero or more IAM policies to the role.
resource "aws_iam_role_policy_attachment" "this" {
  for_each   = toset(var.policy_arns)
  role       = aws_iam_role.this.name
  policy_arn = each.value
}
