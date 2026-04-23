
terraform {
  backend "s3" {
    bucket         = "shopease-webapp-tfstate-dev-483829975256"
    key            = "development/terraform.tfstate"
    region         = "ap-south-1"
    dynamodb_table = "shopease-webapp-tfstate-lock-dev-483829975256"
    encrypt        = true
  }
}
