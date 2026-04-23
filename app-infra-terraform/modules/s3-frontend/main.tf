# ########################################
# # S3 Module - Frontend Build Bucket
# ########################################

resource "aws_s3_bucket" "frontend_bucket" {
  bucket = "shopease-frontend-dev-483829975256"

  tags = {
    Name        = "shopease-frontend"
    Environment = "dev"
  }
}