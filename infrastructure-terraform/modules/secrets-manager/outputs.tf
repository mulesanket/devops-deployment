output "arn" {
  description = "ARN of the AWS Secrets Manager secret. Used by IRSA policy and ESO ExternalSecret."
  value       = aws_secretsmanager_secret.this.arn
}

output "name" {
  description = "Name of the AWS Secrets Manager secret."
  value       = aws_secretsmanager_secret.this.name
}

output "id" {
  description = "Internal ID of the secret."
  value       = aws_secretsmanager_secret.this.id
}
