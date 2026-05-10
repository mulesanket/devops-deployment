module "ecr" {
  source           = "../../modules/ecr"
  project_name     = var.project_name
  environment      = var.environment
  repository_names = ["auth-service", "product-service", "cart-service", "order-service"]
  max_image_count  = 10
}
