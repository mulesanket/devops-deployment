# ============================================
# Shopease: CI/CD agent IRSA + S3 artifact bucket
#
# Author: Sanket Mule
# --------------------------------------------
# Resources:
#  1. S3 bucket for build artifacts (.jar, SBOMs, reports)
#  2. IAM policy: ECR push (all shopease repos) + S3 put on the bucket above
#  3. IRSA role bound to the K8s SA `jenkins-agent-builder`
#     in namespace `jenkins-cicd-agents`
#
# After `terraform apply`:
#  - Annotate the SA with the role ARN (see annotation snippet in outputs)
#  - Every build pod that uses this SA inherits ECR push + S3 put perms
#    via short-lived STS tokens — no static AWS keys anywhere.
# ============================================

locals {
  ci_namespace            = "jenkins-cicd-agents"
  ci_service_account_name = "jenkins-agent-builder"
  artifacts_bucket_name   = "${var.project_name}-${var.environment}-ci-artifacts"
}

# ---------------------------------------------------------------------------
# 1. S3 bucket for CI build artifacts
# ---------------------------------------------------------------------------
resource "aws_s3_bucket" "ci_artifacts" {
  bucket        = local.artifacts_bucket_name
  force_destroy = true

  tags = {
    Name        = local.artifacts_bucket_name
    Environment = var.environment
    Component   = "ci-cd"
    ManagedBy   = "terraform"
  }
}

resource "aws_s3_bucket_versioning" "ci_artifacts" {
  bucket = aws_s3_bucket.ci_artifacts.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "ci_artifacts" {
  bucket                  = aws_s3_bucket.ci_artifacts.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "ci_artifacts" {
  bucket = aws_s3_bucket.ci_artifacts.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# Lifecycle: move artifacts to cheaper storage after 30 days, delete after 90.
resource "aws_s3_bucket_lifecycle_configuration" "ci_artifacts" {
  bucket = aws_s3_bucket.ci_artifacts.id

  rule {
    id     = "ci-artifacts-tiering"
    status = "Enabled"

    filter {} # apply to all objects

    transition {
      days          = 30
      storage_class = "STANDARD_IA"
    }

    expiration {
      days = 90
    }

    noncurrent_version_expiration {
      noncurrent_days = 30
    }
  }
}

# ---------------------------------------------------------------------------
# 2. IAM policy: ECR push + S3 put (scoped)
# ---------------------------------------------------------------------------
data "aws_caller_identity" "current" {}

data "aws_iam_policy_document" "ci_agent" {
  # ECR auth token is a CLUSTER-WIDE action (no resource ARN possible)
  statement {
    sid       = "EcrGetAuthToken"
    effect    = "Allow"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # ECR push + read, restricted to shopease repos in this account/region
  statement {
    sid    = "EcrPushAndRead"
    effect = "Allow"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:DescribeRepositories",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:ListImages",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = [
      "arn:aws:ecr:${var.aws_region}:${data.aws_caller_identity.current.account_id}:repository/${var.project_name}-${var.environment}-*"
    ]
  }

  # S3 write to the artifacts bucket only
  statement {
    sid    = "S3ArtifactsReadWrite"
    effect = "Allow"
    actions = [
      "s3:GetObject",
      "s3:PutObject",
      "s3:DeleteObject",
      "s3:AbortMultipartUpload",
      "s3:ListMultipartUploadParts",
    ]
    resources = ["${aws_s3_bucket.ci_artifacts.arn}/*"]
  }

  statement {
    sid       = "S3ArtifactsList"
    effect    = "Allow"
    actions   = ["s3:ListBucket", "s3:GetBucketLocation"]
    resources = [aws_s3_bucket.ci_artifacts.arn]
  }
}

resource "aws_iam_policy" "ci_agent" {
  name        = "${var.project_name}-${var.environment}-ci-agent"
  description = "Permissions for Jenkins build agent pods: ECR push + S3 artifact upload."
  policy      = data.aws_iam_policy_document.ci_agent.json
}

# ---------------------------------------------------------------------------
# 3. IRSA role
# ---------------------------------------------------------------------------
module "ci_agent_irsa" {
  source = "../../modules/irsa"

  role_name            = "${var.project_name}-${var.environment}-ci-agent-irsa"
  oidc_provider_arn    = module.eks.oidc_provider_arn
  oidc_provider_url    = module.eks.oidc_provider_url
  namespace            = local.ci_namespace
  service_account_name = local.ci_service_account_name
  policy_arns          = [aws_iam_policy.ci_agent.arn]

  tags = {
    Component   = "ci-cd"
    Environment = var.environment
  }

  depends_on = [module.eks]
}

# ---------------------------------------------------------------------------
# 4. Outputs (for SA annotation + Jenkinsfile)
# ---------------------------------------------------------------------------
output "ci_agent_irsa_role_arn" {
  description = "IAM role ARN to annotate on the jenkins-agent-builder ServiceAccount."
  value       = module.ci_agent_irsa.role_arn
}

output "ci_artifacts_bucket_name" {
  description = "S3 bucket for CI build artifacts (jars, SBOMs, scan reports)."
  value       = aws_s3_bucket.ci_artifacts.bucket
}
