
output "frontend_bucket_id" {
  value = aws_s3_bucket.frontend_bucket.id
}

output "bucket_regional_domain_name" {
  value = aws_s3_bucket.frontend_bucket.bucket_regional_domain_name
}

output "frontend_bucket_arn" {
  value = aws_s3_bucket.frontend_bucket.arn
}