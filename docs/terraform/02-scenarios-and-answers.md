# Terraform — Scenario-Based Interview Questions & Answers

> Format: prompt → what they're probing → structured answer in 60–120 s.
> Anchored in ShopEase's actual Terraform.

---

## Scenario 1 — "Two engineers run `terraform apply` at the same time. What happens?"

**What they're probing:** Backend + state locking understanding.

**Answer:**
- With a properly configured **S3 + DynamoDB backend**, the first apply acquires a lock row in the DynamoDB table; the second apply sees the lock and errors out with *"Error acquiring the state lock"*.
- This prevents the classic disaster: two applies writing different state files to the same key, corrupting the source of truth.
- If a process dies mid-apply and leaves a stuck lock, `terraform force-unlock <LOCK_ID>` (after confirming nothing's actually running).
- **ShopEase** uses exactly this: state in `s3://shopease-terraform-state/development/terraform.tfstate`, lock in DynamoDB table with `LockID` as primary key. Versioning is on so I can recover from accidental state damage.

---

## Scenario 2 — "Someone manually changed a resource in the AWS console. What does `terraform plan` show?"

**What they're probing:** Refresh, drift, and remediation strategy.

**Answer:**
- `terraform plan` runs a **refresh** first: it reads current state of every resource from AWS, compares to state file. If the live world differs from state, plan shows it.
- Then plan compares state (now refreshed) against the *config* (the `.tf` files). The diff is what apply would do — Terraform's goal is to make live match config.
- **Net effect:** the console change gets *reverted* by `apply` unless you update the config to match.
- **What I do in practice:** see the drift in plan, talk to whoever made the change, then either (a) codify it (update `.tf` to match), or (b) revert by applying. Never just apply without that conversation — could break a fix someone made under pressure.
- For ShopEase I don't allow console changes outside emergencies; the only manual changes I've made are the few imports during the KMS recovery.

---

## Scenario 3 — "How do you handle secrets in Terraform (input variables) without committing them to Git?"

**What they're probing:** State file sensitivity awareness.

**Answer:** Layers:
1. **Variable declaration**: `sensitive = true` so Terraform redacts the value from CLI output.
2. **Value source**: never in `terraform.tfvars` (committed). Use one of:
   - `TF_VAR_db_master_password` env var (CI sets it from its own secret store).
   - `-var-file=secrets.auto.tfvars` excluded by `.gitignore`.
   - SSM Parameter Store or Secrets Manager pulled via `data` source (best — the secret never leaves AWS).
3. **State file**: the value still ends up in `terraform.tfstate` in plaintext. Mitigate via:
   - **S3 backend encrypted with KMS** (`encrypt = true`).
   - **Bucket policy restricting access** to specific IAM roles only.
   - **Bucket versioning** so accidental deletes are recoverable.
4. **In ShopEase:** `db_master_password` is `sensitive = true`, sourced from a tfvars file kept out of Git. The bucket is encrypted, versioned, and only the Terraform role can read it.

---

## Scenario 4 — "Your apply fails halfway. What's the state of your infrastructure?"

**What they're probing:** Failure modes and recovery.

**Answer:**
- Terraform applies resources in **dependency order**. Whatever succeeded is recorded in state. Whatever was in progress when failure hit is either:
  - **Created in AWS but not in state** (Terraform died after AWS responded but before writing state) — you'll see "resource already exists" on next apply. Fix with `terraform import`.
  - **Tainted in state** (recorded but Terraform knows it's bad) — next apply will replace it.
- **Recovery:**
  1. Run `terraform plan` — it shows current state vs config; the failure path is usually obvious.
  2. Read error carefully: is it AWS quota? IAM denied? Bad input? Fix root cause.
  3. Re-run `terraform apply`. Terraform is idempotent — resources already created are skipped.
- **Worst case** (apply died, state file half-written): the S3 backend has versioning — restore the previous version.
- **Defensive habit:** before any non-trivial apply, `terraform state pull > backup-$(date).tfstate` so you have a known-good copy locally.

---

## Scenario 5 — "How do you reuse Terraform across dev/staging/prod?"

**What they're probing:** Multi-environment strategy.

**Answer:** Two competing patterns:

**Pattern A — Workspaces** (`terraform workspace new prod`): one config, multiple state files. Quick but dangerous — easy to apply prod thinking you're in dev. Workspaces should be used for ephemeral / per-developer state, not for environment isolation.

**Pattern B — Folder per environment** (what ShopEase uses):
```
environments/
├── development/   # backend.tf points to dev state, terraform.tfvars has dev values
└── production/    # backend.tf points to prod state, terraform.tfvars has prod values
```
Both call the same `modules/`. Pros: physical separation, distinct AWS credentials per environment in CI, can't accidentally cross-target. Cons: some duplication in `*.tf` files (mitigated by keeping environment files thin — they just wire modules).

**My production-grade tweak:** prod has its own AWS account, its own state bucket, its own IAM roles. The Terraform code is identical except for variable values.

---

## Scenario 6 — "When do you write a module vs inline?"

**What they're probing:** Module design judgment.

**Answer:** Rule of thumb: **write a module when you'd otherwise copy-paste**. Three concrete tests:

1. **Reuse test** — will this be used in more than one place? If yes, module.
2. **Abstraction test** — does it represent a meaningful business unit ("a VPC", "an IRSA role", "a secret")? If yes, module even if used once — future-you will thank present-you.
3. **Primitives test** — is it just one or two AWS primitives? Then probably inline. Don't wrap `aws_iam_policy` in a module — that's just adding indirection.

**ShopEase examples:**
- `modules/irsa/` — clear yes. Used twice, encapsulates the trust policy boilerplate.
- `aws_iam_policy` resources — kept inline. They're primitives; wrapping them hides nothing useful.
- `modules/secrets-manager/` — yes, called 4 times.
- `module.app_secrets_kms` — yes, even though called once. The "alias + key + rotation" combo is a meaningful unit and I'll add more keys later.

---

## Scenario 7 — "How do you handle a resource that needs to exist before Terraform runs (chicken-and-egg)?"

**What they're probing:** Bootstrap pattern.

**Answer:** Classic example: **the S3 bucket for the remote state**. Terraform's backend config references the bucket — but Terraform also wants to manage the bucket.

**Patterns:**
1. **Manual bootstrap**: create the bucket + DynamoDB table by hand once, then `terraform import` them into a tiny bootstrap config. Live with the small manual step.
2. **Separate bootstrap config** with **local state**: a `bootstrap/` folder with `backend` set to `local`. Creates the bucket + table, writes its state to a local file, commits the state to Git (it's not sensitive — just a bucket and a table).
3. **AWS CDK / CloudFormation** for the bootstrap, Terraform for everything else.
4. **OpenTofu / Terragrunt** can simplify with auto-init helpers.

**For ShopEase:** Pattern 1 — I created the bucket and DynamoDB table manually once. It's a five-minute job per account and never repeats.

---

## Scenario 8 — "You added `count = var.enabled ? 1 : 0` to a module call. What can go wrong?"

**What they're probing:** Resource addressing and refactors.

**Answer:**
- With `count`, resources are addressed `module.foo[0].aws_xxx.bar`. With `for_each`, they're `module.foo["key"].aws_xxx.bar`.
- **If you flip from inline → `count = 1`**, every resource address changes. Terraform sees the old address as "destroyed" and the new as "created" — even though it's the same resource. Disaster for stateful things.
- **Fix:** `terraform state mv aws_xxx.bar 'module.foo[0].aws_xxx.bar'` (or use `moved` blocks, the modern way).
- **`moved` block** (declarative, lives in config):
  ```hcl
  moved {
    from = aws_xxx.bar
    to   = module.foo[0].aws_xxx.bar
  }
  ```
  Terraform reconciles it during plan; no manual state surgery.
- This came up exactly during the KMS module refactor — using `moved` blocks would have avoided my disaster recovery story.

---

## Scenario 9 — "Walk me through how IRSA is wired in your Terraform."

**What they're probing:** Real cross-module integration.

**Answer:** Three layers:

1. **EKS cluster module** emits `oidc_provider_arn` and `oidc_provider_url`. These come from the `aws_iam_openid_connect_provider` resource it creates.

2. **IRSA module** takes those plus `namespace`, `service_account_name`, `policy_arns` and produces:
   - An `aws_iam_role` whose trust policy has a federated principal = the OIDC provider, with a condition `sub == system:serviceaccount:<ns>:<sa>`.
   - One `aws_iam_role_policy_attachment` per policy ARN.

3. **Environment composition** (`irsa.tf`, `external-secrets.tf`):
   - Build the policy via `data "aws_iam_policy_document"`.
   - Create `aws_iam_policy`.
   - Call `module "auth_service_irsa"` or `module "external_secrets_irsa"` with that policy ARN.

The K8s side (separate ownership): the ServiceAccount has annotation `eks.amazonaws.com/role-arn` pointing at the role ARN that Terraform output exposes.

**Why I split it this way:** the IRSA module knows about IAM + OIDC. It doesn't know what policies it's attaching. That's the caller's job. One module, two callers, zero duplication.

---

## Scenario 10 — "You need to add a tag to every resource. How?"

**What they're probing:** `default_tags` + tagging strategy.

**Answer:** Two layers:

1. **Provider-level `default_tags`** — applied to every taggable resource the provider creates:
   ```hcl
   provider "aws" {
     default_tags {
       tags = {
         Project     = "shopease-webapp"
         Environment = var.environment
         ManagedBy   = "terraform"
       }
     }
   }
   ```
2. **Resource-level tags** for specifics (`Component`, `Service`, cost-allocation tags).

**Catch:** some resources (`aws_autoscaling_group`, `aws_eks_node_group`) don't propagate `default_tags` automatically — you have to set them explicitly or use `tag` blocks. Always verify in the AWS console after applying a new `default_tags` change.

**ShopEase** could benefit from `default_tags` — currently each module merges its own tag map. Refactor item.

---

## Scenario 11 — "How do you test Terraform changes safely before applying to prod?"

**What they're probing:** Test maturity.

**Answer:** Tiered:

1. **`terraform fmt`** + **`terraform validate`** — catch syntax / type errors.
2. **`tflint`** — catches dead variables, missing required args, AWS-specific anti-patterns.
3. **`checkov` / `tfsec`** — security policy scanners (e.g., flags public S3 buckets, unencrypted volumes).
4. **`terraform plan`** in a PR — human review of every `-/+` line. This is the most important gate.
5. **Apply to a non-prod environment first** — same code, smaller variables. Verify functionally.
6. **`terraform apply -target=...`** for surgical changes during incidents (sparingly — defeats the holistic plan).
7. **`terratest`** (Go-based) for module unit tests — *"can I create this VPC and reach an output value?"* — used for shared modules consumed by many teams.

**For ShopEase 1-engineer scale:** I rely on plan review + dev environment first. `tflint` + `checkov` are roadmap items for CI.

---

## Scenario 12 — "Your `terraform state` is huge and slow. What do you do?"

**What they're probing:** State design at scale.

**Answer:**
- **Identify the bloat** — `terraform state list | wc -l`. State with >500 resources gets slow.
- **Split by blast radius**:
  - `infra-network` — VPC, subnets, NAT.
  - `infra-data` — RDS, S3.
  - `infra-cluster` — EKS, IAM.
  - `infra-app` — namespace-level resources.
- Each gets its own state file and backend key.
- Cross-state references via `terraform_remote_state` data source or, better, **outputs published to SSM Parameter Store** (loose coupling, no Terraform-version sharing required).
- **Caveat:** splitting state introduces ordering — `infra-cluster` depends on `infra-network`. Runbook documents the order.
- **For ShopEase** — single state is fine at current size (~80 resources). I'd split if it grew to ~300+.

---

## Scenario 13 — "Plan shows a destroy + recreate on a resource you don't want to lose. What's happening and what do you do?"

**What they're probing:** Forced replacements and lifecycle blocks.

**Answer:**
- Terraform marks a resource for replacement (`-/+`) when a change is made to a field marked **`ForceNew`** by the provider — e.g., renaming `aws_db_instance.identifier`, changing `aws_eks_node_group.instance_types` (sometimes), KMS key alias name in some cases.
- The plan output annotates the offending field with `# forces replacement`. Identify it.
- **Options:**
  1. **Don't make the change** — find an alternative path (e.g., add a new alias instead of renaming).
  2. **Use `lifecycle { create_before_destroy = true }`** — for resources where a brief duplicate is OK and you want zero downtime.
  3. **`terraform state mv` / `moved` block** — if it's really just an address change, not a real replacement.
  4. **`terraform import`** — destroy in state but keep in AWS, re-import under the new address.
- **War-story angle:** my KMS recovery was exactly this — a destroy was planned, I refused to apply, performed surgical `state rm` + `import` instead.

---

## Scenario 14 — "How do you upgrade a Terraform module version safely?"

**What they're probing:** Versioning hygiene.

**Answer:**
- For **registry modules** or **Git-tagged modules**, pin in `source`:
  ```hcl
  source  = "terraform-aws-modules/vpc/aws"
  version = "~> 5.0"
  ```
- Upgrade flow:
  1. Read the module **CHANGELOG** between current and target versions.
  2. Bump the version constraint.
  3. `terraform init -upgrade` to fetch.
  4. `terraform plan` — examine the diff. Some upgrades trigger replacements; abort if so.
  5. Apply to dev first.
  6. After a soak period, apply to prod.
- For **local modules** (like ShopEase's `modules/`), they're just paths — no versioning. Changes apply immediately on `init`. Tradeoff: simplicity vs no rollback boundary.
- **Production-grade ShopEase**: would extract `modules/` to a separate Git repo with tags once a second team consumes them.

---

## Scenario 15 — "How do you handle Terraform's eventual-consistency issues with AWS?"

**What they're probing:** Real-world AWS friction.

**Answer:** AWS APIs are not strongly consistent — Terraform creates a resource, AWS returns "created", but subsequent reads might return `NotFound` for a few seconds. Common symptoms:

- **IAM role + immediate use** — create role, immediately attach in policy → "Invalid principal in policy" because IAM hasn't propagated. Mitigation: `sleep` provisioner (ugly) or `time_sleep` resource (better) between creation and use.
- **Cross-region resources** — CloudFront + ACM cert in `us-east-1`, infra in `ap-south-1`. Need separate provider aliases.
- **Aurora cluster + cluster instance** — instance creation can fail if cluster status isn't `available` yet. Module needs explicit `depends_on`.

**General principles:**
- Use `depends_on` when implicit dependency isn't enough.
- Use `time_sleep` sparingly for known propagation delays.
- Report flaky resources to Hashicorp / provider — many of these have been fixed in newer AWS provider versions.

For ShopEase: hit this exactly with IAM + IRSA — added a `depends_on = [module.eks]` so the role creation waits for OIDC provider to exist.
