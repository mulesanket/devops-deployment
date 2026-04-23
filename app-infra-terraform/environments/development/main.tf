# #################################################
# # Call Modules here for Development Environment
# #################################################

module "s3_frontend" {
  source = "../../modules/s3-frontend"

  s3_frontend_bucket_name = "shopease-frontend-dev-483829975256"
  environment             = "development"
}

# module "cloudfront" {
#   source = "../../modules/cloudfront"
#   
#   s3_bucket_id                = module.s3_frontend.frontend_bucket_id
#   # other variables...
# }
