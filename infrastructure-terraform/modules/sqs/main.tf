########################################
# SQS Module - Signup Email Queue
########################################

# --- Dead Letter Queue ---
resource "aws_sqs_queue" "dlq" {
  name                      = "${var.project_name}-${var.environment}-${var.queue_name}-dlq"
  message_retention_seconds = 1209600 # 14 days

  tags = {
    Name        = "${var.project_name}-${var.queue_name}-dlq"
    Environment = var.environment
  }
}

# --- Main Queue ---
resource "aws_sqs_queue" "this" {
  name                       = "${var.project_name}-${var.environment}-${var.queue_name}"
  visibility_timeout_seconds = 60
  message_retention_seconds  = 345600 # 4 days
  receive_wait_time_seconds  = 10     # long polling

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq.arn
    maxReceiveCount     = 3
  })

  tags = {
    Name        = "${var.project_name}-${var.queue_name}"
    Environment = var.environment
  }
}

# --- SNS → SQS Subscription ---
resource "aws_sns_topic_subscription" "sns_to_sqs" {
  topic_arn = var.sns_topic_arn
  protocol  = "sqs"
  endpoint  = aws_sqs_queue.this.arn
}
