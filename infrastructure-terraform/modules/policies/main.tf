# ########################################
# # Policies & Policy Attachments
# ########################################

# S3 - Cloudfront Policy 
data "aws_iam_policy_document" "frontend_bucket_policy" {
  statement {
    sid    = "AllowCloudFrontServicePrincipalReadOnly"
    effect = "Allow"

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    actions = [
      "s3:GetObject"
    ]

    resources = [
      "${var.s3_bucket_arn}/*"
    ]

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [var.cloudfront_distribution_arn]
    }
  }
}

# S3 Bucket Policy Attachment
resource "aws_s3_bucket_policy" "frontend_bucket_policy" {
  bucket = var.s3_bucket_id
  policy = data.aws_iam_policy_document.frontend_bucket_policy.json
}

# ########################################
# # SNS Topic Policy
# ########################################

resource "aws_sns_topic_policy" "signup_topic_policy" {
  arn = var.sns_topic_arn

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowSQSSubscription"
        Effect    = "Allow"
        Principal = { Service = "sqs.amazonaws.com" }
        Action    = "SNS:Subscribe"
        Resource  = var.sns_topic_arn
      },
      {
        Sid       = "AllowPublishFromAccount"
        Effect    = "Allow"
        Principal = { AWS = var.account_id }
        Action    = "SNS:Publish"
        Resource  = var.sns_topic_arn
      }
    ]
  })
}

# ########################################
# # SQS Queue Policy - Allow SNS to send
# ########################################

resource "aws_sqs_queue_policy" "signup_queue_policy" {
  queue_url = var.sqs_queue_url

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AllowSNSSendMessage"
        Effect    = "Allow"
        Principal = { Service = "sns.amazonaws.com" }
        Action    = "sqs:SendMessage"
        Resource  = var.sqs_queue_arn
        Condition = {
          ArnEquals = {
            "aws:SourceArn" = var.sns_topic_arn
          }
        }
      }
    ]
  })
}
