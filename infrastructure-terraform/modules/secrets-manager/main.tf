resource "aws_secretsmanager_secret" "this" {
  name                    = var.name
  description             = var.description
  kms_key_id              = var.kms_key_id
  recovery_window_in_days = var.recovery_window_in_days

  tags = merge(
    var.tags,
    {
      Name      = var.name
      ManagedBy = "terraform"
      Component = "secrets-manager"
    }
  )
}

resource "aws_secretsmanager_secret_version" "this" {
  secret_id     = aws_secretsmanager_secret.this.id
  secret_string = jsonencode(var.secret_data)
}

resource "aws_secretsmanager_secret_policy" "this" {
  count      = length(var.allowed_role_arns) > 0 ? 1 : 0
  secret_arn = aws_secretsmanager_secret.this.arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowListedRolesToReadSecret"
        Effect    = "Allow"
        Principal = { AWS = var.allowed_role_arns }
        Action    = "secretsmanager:GetSecretValue"
        Resource  = "*"
      }
    ]
  })
}
