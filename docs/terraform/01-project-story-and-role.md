# ShopEase on AWS — Terraform Project Story & My Role

> **Target audience:** DevOps Engineer interviews, 3–3.5 YOE
> **Use this doc to:** Tell a clean 3-minute IaC story, then deep-dive on any module.

---

## 1. The 60-Second Elevator Pitch

> *"The entire AWS footprint for ShopEase is provisioned with Terraform — VPC, EKS, RDS Aurora, ECR, SNS/SQS, Lambda, SES, S3 + CloudFront for the SPA, KMS, Secrets Manager, IAM/IRSA. I structured the codebase as a **reusable modules layer** with **environment compositions** on top, capability-split files instead of one giant `main.tf`, remote state in S3 with DynamoDB locking, and an enterprise pattern of `data "aws_iam_policy_document"` + inline `aws_iam_policy` resources rather than inline JSON. The whole stack stands up with one `terraform apply` and rebuilds identically in another region or account."*

---

## 2. The "Why" — Problem Statement

The platform needs:
1. **Reproducibility** — dev today, staging tomorrow, prod next quarter. Same code, different `tfvars`.
2. **Auditability** — every infra change as a Git PR with a plan diff attached.
3. **Modularity** — modules reusable across environments and projects (the IRSA module is used twice in dev alone).
4. **Safety** — remote state locking so two engineers can't apply concurrently and corrupt state.
5. **Least privilege** — IAM scoped per workload (IRSA), KMS scoped per data class.
6. **Drift detection** — `terraform plan` should be honest about what's changed since last apply.

That maps to: modular Terraform + S3/DynamoDB backend + environment folders + `for_each`/`count` patterns + `data` sources for type-safe IAM.

---

## 3. Repository Layout

```
infrastructure-terraform/
├── modules/                        # reusable, environment-agnostic building blocks
│   ├── vpc/                        # 3-AZ VPC, public/private subnets, NAT per AZ, IGW
│   ├── eks/                        # cluster + node group + OIDC provider + add-ons
│   ├── eks-iam-role/               # cluster + node IAM roles
│   ├── ecr/                        # for_each over service names → 1 repo each
│   ├── rds/                        # Aurora MySQL cluster + subnet group + SG
│   ├── sns/, sqs/, lambda/, ses/   # async signup-email pipeline
│   ├── s3-frontend/, cloudfront/   # SPA delivery
│   ├── kms/                        # CMK + alias (one per data class)
│   ├── secrets-manager/            # 1 secret = 1 module call (clean reuse)
│   ├── irsa/                       # generic IRSA role factory
│   └── policies/                   # cross-service IAM policy documents
│
└── environments/
    ├── development/                # composition: wire modules together
    │   ├── backend.tf              # S3 + DynamoDB remote state
    │   ├── providers.tf            # aws + helm + kubernetes (for ESO)
    │   ├── variables.tf, terraform.tfvars
    │   ├── outputs.tf              # exports consumed by other repos / humans
    │   ├── network.tf              # vpc module call
    │   ├── compute.tf              # eks_iam_roles + eks
    │   ├── data.tf                 # rds
    │   ├── messaging.tf            # sns + sqs + lambda + ses
    │   ├── registry.tf             # ecr
    │   ├── frontend.tf             # s3-frontend + cloudfront
    │   ├── kms.tf                  # app-secrets CMK
    │   ├── secrets-manager.tf      # 4 secrets + JWT random_password + locals
    │   ├── irsa.tf                 # auth-service IRSA (SNS publish)
    │   └── external-secrets.tf     # ESO IRSA + Helm release
    └── production/                 # (placeholder — same modules, different tfvars)
```

**Key principle:** modules know nothing about environment. Environments know nothing about implementation. Variables flow down, outputs flow up.

---

## 4. What I Personally Built

### 4.1 Remote State (backend.tf)
- **S3 bucket** holds state, versioning ON, default encryption ON.
- **DynamoDB table** for state locking — prevents concurrent applies.
- **Backend block** in `backend.tf` references both. State key is per-environment (`development/terraform.tfstate`).
- Bootstrap chicken-and-egg solved by creating the bucket+table manually once, then importing.

### 4.2 Reusable Modules — Design Conventions

Every module follows the same contract:

```
modules/<name>/
├── main.tf       # resources
├── variables.tf  # inputs with type, description, validation
└── outputs.tf    # outputs with description
```

Conventions I enforced:
- **Every variable has a `description` and `type`** (catches typos at validate time).
- **Every output has a `description`**.
- **No hardcoded provider regions** — provider passed from parent.
- **Tags merged**: module adds its own `Component = "<name>"` tag, environment adds its tags via `merge()`.
- **No `count` games** unless the resource is truly conditional. Prefer `for_each` for collections (typed keys, stable identity).

### 4.3 Highlight Modules

#### `modules/vpc/`
- 3 AZ-spanning public + 3 private subnets.
- One NAT Gateway **per AZ** (not a single shared NAT — that's a SPOF and a cross-AZ data charge).
- Internet Gateway + per-AZ route tables.
- DNS support + DNS hostnames on (required for EKS).

#### `modules/eks/`
- Cluster with private API endpoint preference.
- Managed node group (autoscaling 2→4, `t3.medium`).
- **OIDC provider** registered with IAM — this is what makes IRSA possible. Output `oidc_provider_arn` and `oidc_provider_url` consumed by the `irsa` module.
- Managed add-ons: VPC CNI, CoreDNS, kube-proxy.

#### `modules/irsa/` (the small one that earns its keep)
A reusable factory that takes:
- `role_name`, `oidc_provider_arn`, `oidc_provider_url`
- `namespace`, `service_account_name`
- `policy_arns = []` (list of policies to attach)

It produces:
- An IAM role with the correct **OIDC federation trust policy** (scoped to `system:serviceaccount:<ns>:<sa>`)
- `aws_iam_role_policy_attachment` for each policy ARN (via `count`, not `for_each`, to dodge a "value known after apply" planning bug we hit with policy ARNs from other resources)

Used **twice** in `development/`:
1. `auth_service_irsa` — for the auth pod to publish to SNS.
2. `external_secrets_irsa` — for the ESO controller to read Secrets Manager + decrypt with CMK.

That's the test of a good module: more than one caller, zero copy-paste.

#### `modules/kms/`
- Customer-managed CMK with key rotation enabled.
- Alias `alias/<project>-<env>-<purpose>` (purpose lets us add more keys later — `app-secrets`, `rds`, `s3-logs`).
- Key policy is just "delegate to IAM" — actual permissions live in IAM policies of the consumers. This is the AWS-recommended pattern.

#### `modules/secrets-manager/`
- One secret per module call. Inputs: `name`, `description`, `secret_data` (map), `kms_key_id`, `recovery_window_in_days`.
- `secret_data` is JSON-encoded inside the module — caller just passes a Terraform map.
- Used 4 times in `secrets-manager.tf` (auth/product/cart/order) with `merge()` for tags.

#### `modules/ecr/`
- `for_each` over a list of service names → one repository each.
- `image_tag_mutability = "IMMUTABLE"` — published tags can't be silently overwritten.
- Lifecycle policy keeping last 30 images.

### 4.4 IAM Policy Style — `aws_iam_policy_document` over `jsonencode`

**The "junior" way:**
```hcl
policy = jsonencode({
  Version = "2012-10-17"
  Statement = [{
    Effect = "Allow"
    Action = ["sns:Publish"]
    Resource = module.signup_sns.arn
  }]
})
```

**The way I went:**
```hcl
data "aws_iam_policy_document" "auth_service_sns_publish" {
  statement {
    sid       = "PublishSignupEvents"
    effect    = "Allow"
    actions   = ["sns:Publish"]
    resources = [module.signup_sns.arn]
  }
}

resource "aws_iam_policy" "auth_service_sns_publish" {
  name   = "${var.project_name}-${var.environment}-auth-service-sns-publish"
  policy = data.aws_iam_policy_document.auth_service_sns_publish.json
}
```

**Why:** the `data` source is **type-checked at plan time** — a typo in an action name (`sns:Pulbish`) errors out before any apply. With `jsonencode` you get a runtime IAM rejection.

### 4.5 Environment Composition — Capability-Split Files
Instead of one giant `main.tf`, I split by **capability**:

| File | Contains |
|---|---|
| `network.tf` | `module.vpc` |
| `compute.tf` | `module.eks_iam_roles`, `module.eks` |
| `data.tf` | `module.rds` |
| `messaging.tf` | `module.signup_sns`, `signup_sqs`, `welcome_email_lambda`, `ses` |
| `registry.tf` | `module.ecr` |
| `frontend.tf` | `module.s3_frontend`, `module.cloudfront`, `module.s3_cloudfront_policy` |
| `kms.tf` | `module.app_secrets_kms` |
| `secrets-manager.tf` | 4× secret modules + `random_password.jwt_secret` + locals |
| `irsa.tf` | auth-service IRSA (data source + policy + module) |
| `external-secrets.tf` | ESO IRSA + Helm release |

Cognitive benefit: open one file, see the full story for one capability. Git blame stays focused. PRs touch the file relevant to the change.

### 4.6 Variables & Outputs Discipline
- `variables.tf` — every input declared with `type`, `description`, sometimes `validation`. Sensitive inputs (`db_master_password`) marked `sensitive = true`.
- `terraform.tfvars` — values, not committed if it has secrets (in `.gitignore`). For ShopEase dev it contains only non-sensitive defaults; the DB password comes from `TF_VAR_db_master_password` env var in CI (future).
- `outputs.tf` — exports that other systems consume: `secrets_manager_arns` (a map), `app_secrets_kms_key_arn`, `auth_service_irsa_role_arn`, `external_secrets_irsa_role_arn`, frontend bucket + CloudFront domain. Outputs are the **public API** of the environment.

---

## 5. The Big Refactor — Secrets Phase (Story-Worthy Detail)

What I executed end-to-end:

1. Created `modules/kms` (CMK + alias) and `modules/secrets-manager` (one secret = one module call).
2. Built `secrets-manager.tf` with 4 module calls sharing one CMK; JWT secret generated via `random_password`.
3. Wired `modules/irsa` to ESO with a least-privilege policy: `secretsmanager:GetSecretValue/Describe/ListVersionIds` scoped to `shopease/${env}/*`, plus `kms:Decrypt/DescribeKey` on the CMK only.
4. Added `helm` + `kubernetes` providers, installed ESO via `helm_release` with `atomic + wait + timeout`, IRSA role ARN injected through `set` block.
5. Recovery story: an early refactor would have **destroyed the inline KMS key** that was already encrypting live secrets. I cancelled the in-flight delete, removed the orphan from Terraform state (`terraform state rm`), imported the existing key into the new module path (`terraform import`), re-aliased, and scheduled the orphan for deletion. Zero data loss.

That's the single best Terraform war story I have for interviews.

---

## 6. Numbers Worth Remembering

| Metric | Value |
|---|---|
| Modules written | 14 |
| Environment | 1 active (`development`), prod stubbed |
| AWS resources at full apply | ~80 |
| Apply time (cold) | ~12 min (EKS cluster dominates) |
| Apply time (warm, no diff) | ~30 s (just refresh) |
| Remote state | S3 versioned + DynamoDB lock |
| Provider versions | aws ~> 5.0, helm ~> 3.0, kubernetes ~> 2.30 |

---

## 7. "Tell me about a challenge" — STAR Stories

### Story A — KMS Refactor Disaster Recovery
- **S:** Moved inline `aws_kms_key` into a new `modules/kms` after secrets were already encrypted with the existing key.
- **T:** The plan showed `aws_kms_key.app_secrets` destroy + `module.app_secrets_kms.aws_kms_key.this` create. Applying would render every secret unrecoverable (decryption requires the original key).
- **A:** Aborted the apply. `aws kms cancel-key-deletion` on the scheduled-for-deletion key. `terraform state rm aws_kms_key.app_secrets` to detach Terraform's claim on the original. `terraform import module.app_secrets_kms.aws_kms_key.this <key-id>` to bring the live key under the new module path. `aws kms update-alias` to point the alias at the same key. Re-ran plan → clean.
- **R:** Secrets remained decryptable throughout. Lesson: any plan touching `aws_kms_key`, `aws_rds_cluster`, or anything else stateful + crypto-bound gets a manual review of every `-/+` line before approve.

### Story B — `for_each` "value known after apply" trap
- **S:** Built the IRSA module with `for_each = toset(var.policy_arns)` on `aws_iam_role_policy_attachment`.
- **T:** First plan against a fresh environment errored: *"The `for_each` value depends on resource attributes that cannot be determined until apply"* — because policy ARNs came from `aws_iam_policy.*.arn` that didn't exist yet.
- **A:** Two valid fixes: (1) use `-target` to create policies first, then apply the rest — bad, requires human ordering; (2) switch to `count = length(var.policy_arns)` — Terraform tolerates known-after-apply with `count` even when it doesn't with `for_each` (current behaviour; this may change in future versions).
- **R:** Switched to `count`. Module is now applied in one shot from cold. Documented the trap in the module README.

### Story C — Capability-Split vs `main.tf`
- **S:** Original `environments/development/main.tf` was 400+ lines, mixing networking, compute, data, messaging.
- **T:** PRs were noisy and Git blame useless — every change touched the same file.
- **A:** Split by capability into 10 small files (`network.tf`, `compute.tf`, etc.). No logical change, pure reorganization. Verified `terraform plan` reported zero diffs after the split (file split doesn't affect resource addresses).
- **R:** Subsequent PRs touch one or two files. Blame is useful again. Onboarding faster — new engineers find the messaging code in `messaging.tf` without grep.

---

## 8. What I'd Do Next

1. **Stand up `environments/production/`** — same modules, prod-sized variables, separate state.
2. **CI/CD for Terraform** — Atlantis or GitHub Actions: `plan` on PR, `apply` on merge to main, with approval gate.
3. **Pre-commit hooks** — `terraform fmt`, `tflint`, `checkov` for security policy.
4. **Move backend bootstrap into code** — a tiny separate Terraform with local state that creates the S3 bucket + DynamoDB table (or use AWS CDK once to bootstrap).
5. **OIDC for CI** — GitHub Actions assumes a role via OIDC instead of long-lived IAM keys.
6. **State refactoring** — split state per blast-radius (network/data/cluster) if the project grows; today one state is fine.
7. **Module versioning** — pin modules to Git tags once they're consumed by multiple projects.

---

## 9. My Role Statement (1-liner)

> *"I authored the Terraform codebase end-to-end — designed the modules layer, the environment composition pattern, the remote-state setup, and integrated AWS Secrets Manager + KMS + IRSA + External Secrets Operator into one declarative apply."*
