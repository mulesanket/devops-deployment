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