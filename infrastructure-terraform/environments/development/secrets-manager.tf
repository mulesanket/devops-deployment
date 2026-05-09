resource "random_password" "jwt_secret" {
  length  = 64
  special = false
}

locals {
  secret_name_prefix = "shopease/${var.environment}"
}

resource "aws_secretsmanager_secret" "auth_service" {
  name        = "${local.secret_name_prefix}/auth-service"
  description = "Auth service runtime secrets (DB password, JWT signing key)"

  recovery_window_in_days = 7

  tags = {
    Service     = "auth-service"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

resource "aws_secretsmanager_secret_version" "auth_service" {
  secret_id = aws_secretsmanager_secret.auth_service.id
  secret_string = jsonencode({
    SPRING_DATASOURCE_PASSWORD = var.db_master_password
    APP_JWT_SECRET             = random_password.jwt_secret.result
  })
}

resource "aws_secretsmanager_secret" "product_service" {
  name        = "${local.secret_name_prefix}/product-service"
  description = "Product service runtime secrets (DB password)"

  recovery_window_in_days = 7

  tags = {
    Service     = "product-service"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

resource "aws_secretsmanager_secret_version" "product_service" {
  secret_id = aws_secretsmanager_secret.product_service.id
  secret_string = jsonencode({
    SPRING_DATASOURCE_PASSWORD = var.db_master_password
  })
}

resource "aws_secretsmanager_secret" "cart_service" {
  name        = "${local.secret_name_prefix}/cart-service"
  description = "Cart service runtime secrets (DB password, JWT signing key)"

  recovery_window_in_days = 7

  tags = {
    Service     = "cart-service"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

resource "aws_secretsmanager_secret_version" "cart_service" {
  secret_id = aws_secretsmanager_secret.cart_service.id
  secret_string = jsonencode({
    SPRING_DATASOURCE_PASSWORD = var.db_master_password
    APP_JWT_SECRET             = random_password.jwt_secret.result
  })
}

resource "aws_secretsmanager_secret" "order_service" {
  name        = "${local.secret_name_prefix}/order-service"
  description = "Order service runtime secrets (DB password, JWT signing key)"

  recovery_window_in_days = 7

  tags = {
    Service     = "order-service"
    Environment = var.environment
    ManagedBy   = "terraform"
  }
}

resource "aws_secretsmanager_secret_version" "order_service" {
  secret_id = aws_secretsmanager_secret.order_service.id
  secret_string = jsonencode({
    SPRING_DATASOURCE_PASSWORD = var.db_master_password
    APP_JWT_SECRET             = random_password.jwt_secret.result
  })
}
