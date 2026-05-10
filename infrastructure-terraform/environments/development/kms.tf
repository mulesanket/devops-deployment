module "app_secrets_kms" {
  source = "../../modules/kms"

  alias       = "${var.project_name}-${var.environment}-app-secrets"
  description = "Customer-managed key for ShopEase application secrets in ${var.environment}"

  deletion_window_in_days = 30
  enable_key_rotation     = true

  tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}
