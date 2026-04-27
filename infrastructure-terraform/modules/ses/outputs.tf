output "ses_identity_arn" {
  description = "ARN of the verified SES email identity"
  value       = aws_ses_email_identity.sender.arn
}
