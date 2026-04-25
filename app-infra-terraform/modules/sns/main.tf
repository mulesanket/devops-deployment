########################################
# SNS Module - Signup Notification Topic
########################################

resource "aws_sns_topic" "this" {
  name = "${var.project_name}-${var.environment}-${var.topic_name}"

  tags = {
    Name        = "${var.project_name}-${var.topic_name}"
    Environment = var.environment
  }
}
