module "signup_sns" {
  source       = "../../modules/sns"
  project_name = var.project_name
  environment  = var.environment
}

module "signup_sqs" {
  source        = "../../modules/sqs"
  project_name  = var.project_name
  environment   = var.environment
  sns_topic_arn = module.signup_sns.topic_arn
}

module "welcome_email_lambda" {
  source             = "../../modules/lambda"
  project_name       = var.project_name
  environment        = var.environment
  lambda_source_path = "${path.module}/../../../application-backend/lambda/welcome_email.py"
  sqs_queue_arn      = module.signup_sqs.queue_arn
  sender_email       = var.ses_sender_email

  depends_on = [module.signup_sqs]
}

module "ses" {
  source       = "../../modules/ses"
  sender_email = var.ses_sender_email
}
