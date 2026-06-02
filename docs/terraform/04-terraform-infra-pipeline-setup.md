# Terraform Infrastructure Pipeline Explanation

## 1. Overview

In our project, infrastructure was managed using Terraform and executed through CI/CD-based infrastructure pipelines.

Terraform code was maintained in Git, and the approved source of truth was the `master` branch. Engineers created feature branches for infrastructure changes, but feature branches were used only for validation and plan review. Actual infrastructure changes were applied only after the code was reviewed and merged to `master`.

The infrastructure pipeline was designed with a clear separation between:

1. Feature branch validation
2. Environment-specific plan
3. Environment-specific apply

This helped us avoid accidental infrastructure changes and gave us a proper review process before applying changes to AWS.

---

## 2. How Infrastructure Pipelines Were Set Up

For each environment, we had separate Terraform pipelines.

For example:

```text
Development
├── development-plan-non-master
├── development-plan
└── development-apply

Testing
├── testing-plan-non-master
├── testing-plan
└── testing-apply

Staging
├── staging-plan-non-master
├── staging-plan
└── staging-apply

Production
├── production-plan-non-master
├── production-plan
└── production-apply
```

Each environment had its own plan and apply pipeline.

The basic idea was:

```text
Feature branch → non-master plan only
Master branch  → environment plan
Master branch  → environment apply
```

---

## 3. Why We Had Separate Plan and Apply Pipelines

We separated `plan` and `apply` for safety.

The `plan` pipeline only shows what Terraform is going to create, update, or delete.

The `apply` pipeline actually makes changes in AWS.

This separation helps the team review the plan output before making real infrastructure changes.

For example:

```bash
terraform plan
```

shows:

```text
+ create resource
~ update resource
- destroy resource
```

But it does not make any changes.

Only this command changes infrastructure:

```bash
terraform apply
```

---

## 4. Feature Branch / Non-Master Plan Flow

Whenever we needed to make an infrastructure change, we created a feature branch from `master`.

Example:

```bash
git checkout master
git pull origin master
git checkout -b feature/add-s3-bucket
```

Then we made Terraform code changes and pushed the feature branch.

```bash
git add .
git commit -m "Add S3 bucket"
git push origin feature/add-s3-bucket
```

After pushing the feature branch, the non-master plan pipeline was triggered.

Example:

```text
development-plan-non-master
```

This pipeline checked out the feature branch and ran Terraform plan against the development environment state.

Important point:

```text
Terraform plan does not compare feature branch with master branch.
Terraform plan compares the checked-out Terraform code with the remote Terraform state.
```

So in this case:

```text
feature branch Terraform code
        vs
development Terraform remote state
```

The non-master plan pipeline only validates the change. It does not apply anything.

---

## 5. What Non-Master Plan Pipeline Does

The non-master plan pipeline usually performs these steps:

```text
1. Checkout Terraform code from feature branch
2. Load environment-specific parameters
3. Configure AWS credentials or assume role
4. Initialize Terraform with remote backend
5. Validate Terraform code
6. Run terraform plan
7. Publish or show plan output
8. Stop without applying changes
```

Example commands:

```bash
terraform init
terraform validate
terraform plan
```

In our reference setup, the non-master plan pipeline used environment-specific values like:

```text
Environment    = development
Domain         = SchoolSpider
Region         = ap-south-1
Backend Bucket = infrastructure-school-spider-development
Backend Key    = terraform_state/school-spider_development.tfstate
```

So it was running plan against the development environment state.

---

## 6. Master Branch Plan Flow

After the feature branch plan was reviewed and approved, the feature branch was merged into `master`.

Example:

```text
feature/add-s3-bucket → master
```

Once the change was merged to `master`, the environment-specific plan pipeline was executed.

Example:

```text
development-plan
```

This pipeline checked out the approved code from `master` and compared it with the development remote state.

So the comparison became:

```text
master branch Terraform code
        vs
development Terraform remote state
```

This gave the final plan from the approved branch before applying the change.

---

## 7. Apply Pipeline Flow

After the plan output was reviewed, the apply pipeline was triggered.

Example:

```text
development-apply
```

The apply pipeline checked out code from `master`, initialized Terraform with the same development backend, and ran Terraform apply.

This actually created, updated, or deleted AWS resources.

After successful apply, Terraform updated the development remote state file.

Example:

```text
s3://infrastructure-elements-development/terraform_state/AccountPeriods_development.tfstate
```

After apply, if we run plan again, Terraform should ideally show:

```text
No changes. Infrastructure is up-to-date.
```

---

## 8. Complete Flow for Adding an S3 Bucket

Suppose the requirement is:

```text
Add a new S3 bucket for reports.
```

The flow would be:

```text
1. Create feature branch from master
2. Add Terraform code for S3 bucket
3. Push feature branch
4. Non-master plan pipeline runs
5. Review plan output
6. Merge feature branch to master
7. Environment plan pipeline runs from master
8. Review final plan
9. Trigger apply pipeline
10. Terraform creates the S3 bucket in AWS
11. Terraform updates remote state
```

Flow diagram:

```text
feature/add-s3-bucket
        |
        | push
        v
development-plan-non-master
        |
        | terraform plan only
        | no apply
        v
review plan output
        |
        v
merge to master
        |
        v
development-plan
        |
        | terraform plan from master
        v
review final plan
        |
        v
development-apply
        |
        | terraform apply
        v
AWS infrastructure updated
        |
        v
development Terraform state updated
```

---

## 9. How Environments Were Differentiated

Environments were not separated mainly by Git branches.

They were separated by:

```text
1. Different pipeline/job
2. Different backend state
3. Different variable values
4. Different AWS account or IAM role
5. Different region
6. Different resource naming convention
7. Different tags
```

For example:

```text
development-plan
    uses development backend state
    uses development variables
    uses development AWS account/role
    creates development resources

testing-plan
    uses testing backend state
    uses testing variables
    uses testing AWS account/role
    creates testing resources

production-plan
    uses production backend state
    uses production variables
    uses production AWS account/role
    creates production resources
```

---

## 10. Example of Environment Separation

Same Terraform code can be reused for all environments.

Example module:

```hcl
module "reports_bucket" {
  source       = "../../modules/s3"
  bucket_name  = var.reports_bucket_name
  environment  = var.environment
}
```

Development variables:

```hcl
environment         = "development"
reports_bucket_name = "accountperiods-dev-reports"
```

Testing variables:

```hcl
environment         = "testing"
reports_bucket_name = "accountperiods-test-reports"
```

Staging variables:

```hcl
environment         = "staging"
reports_bucket_name = "accountperiods-stage-reports"
```

Production variables:

```hcl
environment         = "production"
reports_bucket_name = "accountperiods-prod-reports"
```

So the Terraform module is the same, but each pipeline passes different environment-specific values.

That is how different resources are created in different environments.

---

## 11. How Terraform State Was Separated

Each environment had its own Terraform state.

For example:

```text
development state:
terraform_state/AccountPeriods_development.tfstate

testing state:
terraform_state/AccountPeriods_testing.tfstate

staging state:
terraform_state/AccountPeriods_staging.tfstate

production state:
terraform_state/AccountPeriods_production.tfstate
```

This is very important because Terraform uses state to understand what resources it manages.

Development pipeline reads only development state.

Testing pipeline reads only testing state.

Production pipeline reads only production state.

So development changes do not directly affect production state.

---

## 12. How Resources Were Separated

Resources were separated using environment-specific names and tags.

Example S3 bucket names:

```text
accountperiods-dev-reports
accountperiods-test-reports
accountperiods-stage-reports
accountperiods-prod-reports
```

Example tags:

```hcl
tags = {
  Environment = var.environment
  Domain      = var.domain
  ManagedBy   = "Terraform"
}
```

So even though the Terraform code was common, the actual AWS resources were different for each environment.

---

## 13. Typical Stages of Infra Pipeline

A typical Terraform infra pipeline had these stages:

```text
1. Checkout Terraform code
2. Load environment-specific parameters
3. Run security or quality checks
4. Configure AWS credentials or assume IAM role
5. Run terraform init with remote backend
6. Run terraform validate
7. Run terraform plan
8. Review plan output
9. Manual approval for apply
10. Run terraform apply
11. Update remote state
12. Publish plan/report artifacts
```

For feature branch pipeline:

```text
Checkout
→ Security checks
→ Terraform init
→ Terraform validate
→ Terraform plan
→ Publish plan output
→ Stop
```

For apply pipeline:

```text
Checkout master
→ Terraform init
→ Terraform apply
→ Update remote state
```

---

## 14. How Change Was Promoted Across Environments

After the code was merged to `master`, the same approved Terraform code was promoted environment by environment.

Example flow:

```text
development-plan
        ↓
development-apply
        ↓
testing-plan
        ↓
testing-apply
        ↓
staging-plan
        ↓
staging-apply
        ↓
production-plan
        ↓
approval
        ↓
production-apply
```

Important point:

```text
We do not copy Terraform state from dev to test or prod.
The same approved Terraform code from master is applied separately against each environment's own state.
```

So promotion means:

```text
same master code + dev variables + dev state = development resources

same master code + test variables + test state = testing resources

same master code + prod variables + prod state = production resources
```

---

## 15. Drift Detection Example

During Terraform plan, Terraform refreshes the current AWS resources and compares them with the state and code.

If someone manually changed or deleted a resource outside Terraform, the plan detects drift.

Example:

```text
Objects have changed outside of Terraform
```

This means the actual AWS infrastructure is different from Terraform state or Terraform code.

In that case, Terraform plan may show that it wants to recreate or update resources to bring the infrastructure back to the desired state.

This is useful because Terraform helps detect manual changes made outside the IaC process.

---

## 16. Interview-Ready Explanation

In our project, infrastructure was managed using Terraform and executed through CI/CD pipelines.

The approved Terraform code was maintained in the `master` branch. For any infrastructure change, we created a feature branch and the non-master plan pipeline was triggered. This pipeline checked out the feature branch and ran `terraform init`, `terraform validate`, and `terraform plan` against the target environment's remote state. It was used only for validation and review. It did not apply any changes.

After the plan was reviewed, the feature branch was merged to `master`. Then the environment-specific plan pipeline ran from `master`. For example, `development-plan` used development variables, development AWS account or role, and development backend state. It compared the approved Terraform code with the current development infrastructure state and showed the final plan.

After reviewing the plan, the apply pipeline was triggered. The apply pipeline ran from `master`, executed `terraform apply`, created or updated AWS resources, and updated the remote Terraform state.

Environment separation was handled using separate backend state files, different tfvars or environment variables, different AWS accounts or IAM roles, resource naming conventions, and tags.

So the same Terraform code was promoted across development, testing, staging, and production by running each environment's plan and apply pipeline separately.

---

## 17. Short Interview Answer

Our Terraform infrastructure pipelines followed a plan/apply separation model.

Feature branches triggered only non-master plan jobs. These jobs validated the Terraform code and showed what changes would happen against the target environment state, but they did not apply anything.

After review, changes were merged to `master`. Then environment-specific plan and apply pipelines were used. Each environment had its own backend state, variables, AWS account or IAM role, region, and resource naming.

For example, development used development state and development variables, while production used production state and production variables. This allowed us to use the same Terraform code but keep environments fully isolated.

Actual infrastructure changes were applied only through the apply pipeline, usually after plan review and approval.

---

## 18. Best One-Liner

Feature branch plan was for validation, master plan was for approved review, and apply was for actual infrastructure changes.

Environments were separated using state, variables, credentials, naming, and tags — not by copying Terraform state between environments.
