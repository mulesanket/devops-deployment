output "queue_arn" {
  description = "ARN of the SQS queue"
  value       = aws_sqs_queue.this.arn
}

output "queue_url" {
  description = "URL of the SQS queue"
  value       = aws_sqs_queue.this.url
}

output "queue_name" {
  description = "Name of the SQS queue"
  value       = aws_sqs_queue.this.name
}

output "dlq_arn" {
  description = "ARN of the Dead Letter Queue"
  value       = aws_sqs_queue.dlq.arn
}
