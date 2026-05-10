output "key_arn" {
  description = "ARN of the KMS key. Used by other resources for encryption (kms_key_id)."
  value       = aws_kms_key.this.arn
}

output "key_id" {
  description = "ID of the KMS key."
  value       = aws_kms_key.this.key_id
}

output "alias_name" {
  description = "Friendly alias name (e.g. alias/shopease-webapp-development-app-secrets)."
  value       = aws_kms_alias.this.name
}

output "alias_arn" {
  description = "ARN of the KMS alias."
  value       = aws_kms_alias.this.arn
}
