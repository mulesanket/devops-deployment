########################################
# IRSA Module - Outputs
########################################

output "role_arn" {
  description = "ARN of the IAM role. Annotate the K8s ServiceAccount with: eks.amazonaws.com/role-arn=<this value>"
  value       = aws_iam_role.this.arn
}

output "role_name" {
  description = "Name of the IAM role."
  value       = aws_iam_role.this.name
}
