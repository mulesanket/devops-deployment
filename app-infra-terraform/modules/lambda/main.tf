########################################
# Lambda Module - Welcome Email Function
########################################

# --- Zip the Lambda code ---
data "archive_file" "lambda_zip" {
  type        = "zip"
  source_file = var.lambda_source_path
  output_path = "${path.module}/lambda_function.zip"
}

# --- Lambda IAM Role ---
resource "aws_iam_role" "lambda_role" {
  name = "${var.project_name}-${var.environment}-lambda-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Principal = { Service = "lambda.amazonaws.com" }
      Effect    = "Allow"
    }]
  })

  tags = {
    Name        = "${var.project_name}-lambda-role"
    Environment = var.environment
  }
}

# --- Lambda basic execution (CloudWatch Logs) ---
resource "aws_iam_role_policy_attachment" "lambda_basic_execution" {
  role       = aws_iam_role.lambda_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# --- Lambda permission: read from SQS ---
resource "aws_iam_role_policy" "lambda_sqs_policy" {
  name = "${var.project_name}-${var.environment}-lambda-sqs"
  role = aws_iam_role.lambda_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "sqs:ReceiveMessage",
        "sqs:DeleteMessage",
        "sqs:GetQueueAttributes"
      ]
      Resource = var.sqs_queue_arn
    }]
  })
}

# --- Lambda permission: send email via SES ---
resource "aws_iam_role_policy" "lambda_ses_policy" {
  name = "${var.project_name}-${var.environment}-lambda-ses"
  role = aws_iam_role.lambda_role.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "ses:SendEmail",
        "ses:SendRawEmail"
      ]
      Resource = "*"
    }]
  })
}

# --- Lambda Function ---
resource "aws_lambda_function" "this" {
  function_name    = "${var.project_name}-${var.environment}-welcome-email"
  role             = aws_iam_role.lambda_role.arn
  handler          = "welcome_email.lambda_handler"
  runtime          = "python3.12"
  timeout          = 30
  memory_size      = 128
  filename         = data.archive_file.lambda_zip.output_path
  source_code_hash = data.archive_file.lambda_zip.output_base64sha256

  environment {
    variables = {
      SENDER_EMAIL = var.sender_email
    }
  }

  tags = {
    Name        = "${var.project_name}-welcome-email"
    Environment = var.environment
  }
}

# --- SQS Event Source Mapping (trigger) ---
resource "aws_lambda_event_source_mapping" "sqs_trigger" {
  event_source_arn = var.sqs_queue_arn
  function_name    = aws_lambda_function.this.arn
  batch_size       = 5
  enabled          = true
}
