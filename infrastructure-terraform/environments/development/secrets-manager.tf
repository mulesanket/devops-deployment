resource "random_password" "jwt_secret" {
  length  = 64
  special = false
}

locals {
  secret_name_prefix = "shopease/${var.environment}"

  common_secret_tags = {
    Environment = var.environment
    Project     = var.project_name
  }
}

module "auth_service_secret" {
  source = "../../modules/secrets-manager"

  name        = "${local.secret_name_prefix}/auth-service"
  description = "Auth service runtime secrets (DB password + JWT signing key)"
  kms_key_id  = module.app_secrets_kms.key_arn

  secret_data = {
    SPRING_DATASOURCE_PASSWORD = var.db_master_password
    APP_JWT_SECRET             = random_password.jwt_secret.result
  }

  tags = merge(local.common_secret_tags, {
    Service = "auth-service"
  })
}

module "product_service_secret" {
  source = "../../modules/secrets-manager"

  name        = "${local.secret_name_prefix}/product-service"
  description = "Product service runtime secrets (DB password)"
  kms_key_id  = module.app_secrets_kms.key_arn

  secret_data = {
    SPRING_DATASOURCE_PASSWORD = var.db_master_password
  }

  tags = merge(local.common_secret_tags, {
    Service = "product-service"
  })
}

module "cart_service_secret" {
  source = "../../modules/secrets-manager"

  name        = "${local.secret_name_prefix}/cart-service"
  description = "Cart service runtime secrets (DB password + JWT signing key)"
  kms_key_id  = module.app_secrets_kms.key_arn

  secret_data = {
    SPRING_DATASOURCE_PASSWORD = var.db_master_password
    APP_JWT_SECRET             = random_password.jwt_secret.result
  }

  tags = merge(local.common_secret_tags, {
    Service = "cart-service"
  })
}

module "order_service_secret" {
  source = "../../modules/secrets-manager"

  name        = "${local.secret_name_prefix}/order-service"
  description = "Order service runtime secrets (DB password + JWT signing key)"
  kms_key_id  = module.app_secrets_kms.key_arn

  secret_data = {
    SPRING_DATASOURCE_PASSWORD = var.db_master_password
    APP_JWT_SECRET             = random_password.jwt_secret.result
  }

  tags = merge(local.common_secret_tags, {
    Service = "order-service"
  })
}
