# Terraform — General Interview Questions & Answers

> Target: 3–3.5 YOE DevOps. Project-grounded where useful. Each answer ends with a "what most candidates miss" twist where applicable.

---

## A. Core Concepts

### Q1. What is Terraform and why use it?
Terraform is an **infrastructure-as-code** tool that lets you declare cloud resources in HCL config, computes the diff between desired state and live state, and applies changes via cloud APIs. Why pick it over alternatives:
- **Multi-cloud** — one tool for AWS + GCP + Azure + 100s of providers (CDK is AWS-mainly).
- **Declarative** — you describe the end state, not the steps. Idempotent by design.
- **State-driven plan/apply** — you see the diff before making changes. Reduces "what did I just break?" anxiety.
- **Module ecosystem** — Hashicorp Registry + community modules.

vs **CloudFormation**: Terraform is multi-cloud, plan output is clearer, but you take state-file responsibility (CF state is managed by AWS).

vs **CDK / Pulumi**: those use general-purpose languages; powerful but more rope. Terraform's constrained HCL is harder to abuse.

vs **Ansible**: Ansible is procedural and config-management oriented (configuring servers); Terraform is declarative and provisioning oriented (creating servers). Different tools, different jobs.

---

### Q2. Terraform workflow — `init`, `plan`, `apply`, `destroy`?
- **`init`** — downloads provider plugins, initializes backend, sets up modules. First command in any new working directory or after changing provider/module versions.
- **`plan`** — refreshes state from the cloud, compares to config, prints the diff. Read-only. **Always inspect before apply.**
- **`apply`** — executes the plan. With approval gate by default. `-auto-approve` skips it (use only in automation).
- **`destroy`** — tears down everything in state. Dangerous; usually scoped with `-target` or never used in prod.

Other essentials: `validate` (syntax check), `fmt` (formatter), `output` (read state outputs), `state` (state surgery), `import` (bring existing resources under management), `taint`/`untaint` (mark for replacement).

---

### Q3. What's the Terraform state file? Why does it exist?
The state file (`terraform.tfstate`) is a JSON map of **config addresses → real-world resource IDs + attributes**. Without it, Terraform couldn't:
- Know which AWS resource your config refers to (no AWS field stores "this was created by Terraform module X").
- Detect drift.
- Plan a diff.

**Why store it remotely:** local state on a developer laptop = single point of failure + no team collaboration + no locking. Remote backends (S3 + DynamoDB, Terraform Cloud, GCS) solve all three.

**What most candidates miss:** the state file contains *attribute values* of every resource — including secret values. Treat it as sensitive. Encrypt the bucket, restrict bucket policy, don't paste state file contents in Slack.

---

### Q4. Providers — what are they?
A provider is a plugin that implements CRUD operations for a specific platform's resources (AWS, GCP, Kubernetes, Helm, GitHub, DataDog, etc.). Pinned in `required_providers`:
```hcl
required_providers {
  aws = { source = "hashicorp/aws", version = "~> 5.0" }
}
```

**Aliases** — when you need multiple instances of the same provider (e.g., AWS in two regions):
```hcl
provider "aws" { region = "ap-south-1" }
provider "aws" { alias = "us_east_1", region = "us-east-1" }
resource "aws_acm_certificate" "cf" { provider = aws.us_east_1, ... }
```

ShopEase uses three providers: `aws`, `helm`, `kubernetes`.

---

### Q5. Module — what is it?
A module is a directory containing `.tf` files. **Every Terraform working directory is itself a module** (the "root module"). Calling another directory as a module:
```hcl
module "vpc" {
  source  = "../../modules/vpc"
  cidr    = "10.0.0.0/16"
  azs     = ["a", "b", "c"]
}
```

Module anatomy:
- **`variables.tf`** — inputs (with `type`, `description`, optional `default`/`validation`).
- **`main.tf`** — resources.
- **`outputs.tf`** — outputs (with `description`, optional `sensitive = true`).

**Source types:** local path, Terraform Registry, Git URL, S3, HTTP. Always pin to a version for non-local sources.

---

### Q6. Variables, locals, outputs — when to use each?
- **Variable**: an *input* to the module/environment. Set externally (tfvars, env var, CLI flag).
- **Local**: a *computed value* internal to the module, used to DRY up repeated expressions. Not exposed to callers.
  ```hcl
  locals {
    name_prefix = "${var.project}-${var.environment}"
  }
  ```
- **Output**: a *return value*, exposed to the caller (or human via `terraform output`).

**Rule of thumb:** variable for "things the caller chooses", local for "things I derive", output for "things the caller needs after I'm done".

---

### Q7. `count` vs `for_each` — when to use each?
Both create multiple instances of a resource.

- **`count = N`** — creates N instances addressed by integer (`aws_instance.x[0]`, `[1]`...). Use for *truly numeric* repetition or simple "enabled? 1 : 0".
- **`for_each = toset(["a","b"])`** or `for_each = {key=value}` — addressed by string key (`aws_instance.x["a"]`). Use for collections where each item has stable identity.

**Why `for_each` is usually better:**
- Stable addresses — adding/removing items in the middle of a list with `count` shifts indexes and destroys/recreates wrong resources.
- Self-documenting — `aws_ecr_repository.this["auth-service"]` is clearer than `[0]`.

**When `count` wins:** when `for_each` can't determine its keys at plan time (the "value known after apply" trap). ShopEase's IRSA module uses `count = length(var.policy_arns)` for exactly this reason.

---

### Q8. Data sources vs resources?
- **Resource** — Terraform manages it: creates, updates, destroys.
- **Data source** — Terraform reads it; doesn't manage it. Pulls live state for use elsewhere.

Examples:
```hcl
data "aws_ami" "amazon_linux" { ... }       # find an AMI ID by filters
data "aws_caller_identity" "current" {}     # current account ID + ARN
data "aws_iam_policy_document" "x" { ... }  # build IAM JSON type-safely
```

**ShopEase pattern:** use `data "aws_iam_policy_document"` everywhere instead of `jsonencode()` — type-checked at plan time, refactor-safe.

---

### Q9. What's the difference between `terraform plan` and `terraform apply`?
- **`plan`** is read-only. Refreshes state from cloud, diffs against config, outputs proposed actions. Optionally writes a plan file (`-out=plan.tfplan`) you can apply later.
- **`apply`** executes. If given a plan file, applies exactly that. Without one, runs plan first and asks for approval.

**Pro pattern in CI:** `plan` step generates `plan.tfplan` as an artifact, attached to the PR. Reviewer reads it. On merge, `apply plan.tfplan` runs exactly the reviewed plan — guarantees no drift between review and apply.

---

### Q10. What's `terraform import`? When do you use it?
`terraform import` brings an **existing real-world resource** under Terraform management. You write the resource block in config, then:
```bash
terraform import aws_kms_key.x <key-id>
```
Terraform queries AWS, fetches the resource's current attributes, writes them to state. **It does NOT generate config** — you still write the HCL by hand, and a subsequent plan will tell you about any drift.

**Use cases:**
- Existing resources predate Terraform adoption.
- Bootstrap resources (state bucket, DynamoDB lock table).
- Disaster recovery after `state rm` (like ShopEase's KMS recovery).

**TF 1.5+ `import` block** (declarative, in config):
```hcl
import {
  to = aws_kms_key.x
  id = "<key-id>"
}
```
Cleaner — survives in Git, applies via normal `apply`.

---

## B. State Management

### Q11. Remote state — how do you set it up?
For AWS:
```hcl
terraform {
  backend "s3" {
    bucket         = "shopease-terraform-state"
    key            = "development/terraform.tfstate"
    region         = "ap-south-1"
    encrypt        = true
    dynamodb_table = "terraform-state-locks"
  }
}
```
- **S3** stores the state file (encrypted, versioned).
- **DynamoDB table** stores locks. A single primary-key attribute `LockID` of type String.
- The bucket and table must exist before `init` — chicken-and-egg. Bootstrap manually once per AWS account.

**Backend block is special:** variable interpolation is not allowed in the backend block; values must be literals or provided via `-backend-config=` at init time.

---

### Q12. `terraform state` subcommands — what do they do?
- **`state list`** — show all addresses in state.
- **`state show <addr>`** — dump attributes of one resource.
- **`state mv <from> <to>`** — rename in state. For module refactors and address changes.
- **`state rm <addr>`** — forget about a resource without destroying it in AWS. Use cases: orphaning during refactor, removing imported-by-mistake.
- **`state pull` / `state push`** — read/write the raw state file. Last resort.
- **`state replace-provider`** — when provider source path changes (e.g., Hashicorp registry namespace migration).

**Cardinal rule:** always `terraform state pull > backup.tfstate` before any state surgery.

---

### Q13. What's a `moved` block?
TF 1.1+. Declarative way to tell Terraform "this resource used to be at address A, it's now at address B":
```hcl
moved {
  from = aws_instance.web
  to   = module.compute.aws_instance.web
}
```
On next plan, Terraform records the move in state and shows no destroy/recreate. Lives in Git, reviewable, automatic — much better than `terraform state mv` for refactors.

---

### Q14. What's a backend? Local vs remote?
A **backend** controls where state lives and how operations behave.
- **`local`** (default) — state on disk in `terraform.tfstate`. Fine for personal experiments; broken for teams.
- **`s3`** — S3 + optional DynamoDB lock. Most common AWS setup.
- **`gcs`** — Google Cloud equivalent.
- **`azurerm`** — Azure Blob.
- **`remote`** — Terraform Cloud / Enterprise; also runs `plan`/`apply` server-side (an "enhanced" backend).

Most backends just store state. "Enhanced" backends (TFC/TFE) can also execute operations remotely.

---

### Q15. How do you share outputs between Terraform configs?
Three options:

1. **`terraform_remote_state` data source** — read another state's outputs directly:
   ```hcl
   data "terraform_remote_state" "network" {
     backend = "s3"
     config  = { bucket = "...", key = "network/terraform.tfstate", region = "..." }
   }
   resource "x" "y" { vpc_id = data.terraform_remote_state.network.outputs.vpc_id }
   ```
   Pros: simple. Cons: tight coupling to the producing state's structure + Terraform version.

2. **SSM Parameter Store / Secrets Manager** — producer writes outputs there; consumer reads via `data "aws_ssm_parameter"`. Loose coupling. Recommended for cross-team.

3. **Outputs published to a Terraform module registry** — overkill for most.

For ShopEase: outputs stay within one state today; if I split states by blast radius, I'd use SSM.

---

## C. HCL Language

### Q16. What's the difference between `=` and `:` in HCL?
- **`=`** — argument assignment.
- **`:`** — used in nested map keys for object types, not in regular argument syntax.

Most blocks use `=`. Some legacy resource blocks have nested blocks with no `=`.

---

### Q17. Expressions — what's useful to know?

| Feature | Example |
|---|---|
| String interpolation | `"${var.project}-${var.env}"` |
| Conditional | `var.enabled ? 1 : 0` |
| Splat | `aws_instance.x[*].id` |
| `for` expressions | `{for k, v in var.tags : k => upper(v)}` |
| `merge()` | `merge(local.common, {Service = "auth"})` |
| `try()` | `try(var.optional, "default")` |
| `lookup()` | `lookup(var.map, "key", "default")` |
| `toset()` / `tolist()` | type coercion for `for_each` |
| `jsonencode()` / `jsondecode()` | JSON marshaling |
| `templatefile()` | render a file with vars |

---

### Q18. Variable types — what are they and why specify them?

```hcl
variable "name"    { type = string }
variable "count"   { type = number }
variable "enabled" { type = bool }
variable "azs"     { type = list(string) }
variable "tags"    { type = map(string) }
variable "config"  {
  type = object({
    name = string
    port = number
  })
}
```

**Why bother:** Terraform validates inputs at plan time. A typo in a tfvars value (passing a string where a number is expected) errors out before any apply. Worth the keystrokes.

---

### Q19. Validation blocks?
Inside a variable, constrain values:
```hcl
variable "environment" {
  type = string
  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging, or prod."
  }
}
```
Caught at plan time. Use for enums, CIDR validity, name length limits, anything you don't want to discover during apply.

---

### Q20. `dynamic` blocks?
Generate nested blocks programmatically:
```hcl
resource "aws_security_group" "x" {
  dynamic "ingress" {
    for_each = var.allowed_ports
    content {
      from_port = ingress.value
      to_port   = ingress.value
      protocol  = "tcp"
      cidr_blocks = [var.cidr]
    }
  }
}
```
Use sparingly — they make resources harder to read. Prefer flat lists when possible.

---

## D. Resource Lifecycle

### Q21. The `lifecycle` block — what does each setting do?
```hcl
lifecycle {
  create_before_destroy = true     # for zero-downtime replacements
  prevent_destroy       = true     # safety net against accidental destroy
  ignore_changes        = [tags]   # tolerate drift on specific fields
  replace_triggered_by  = [aws_x.y.foo]  # force replacement when something else changes
}
```

- **`create_before_destroy`**: new resource comes up before old goes down. Tricky for resources with unique-name constraints (`name` field) — combine with a name suffix.
- **`prevent_destroy`**: hard block. Apply errors out if it would destroy. Use for prod databases, KMS keys.
- **`ignore_changes`**: useful when other tooling modifies a field (e.g., autoscaling changes desired capacity, tagging by Cost Explorer).
- **`replace_triggered_by`**: replace this resource when a referenced attribute changes — e.g., replace an EC2 when its user_data hash changes.

---

### Q22. What's a "tainted" resource?
A resource marked for replacement on next apply. Was a manual `terraform taint <addr>` command; modern equivalent is `terraform apply -replace=<addr>`.

Use when you know a resource is in a bad state (e.g., manually broken in the console) and want Terraform to recreate it. Better than `destroy` + `apply` because dependencies stay intact.

---

### Q23. Provisioners — what are they and should you use them?
`provisioner "local-exec"` / `"remote-exec"` / `"file"` — run scripts as part of resource creation/destruction.

**Hashicorp's own guidance: avoid.** They're imperative escapes from declarative IaC. Failure modes are nasty (script runs once, fails, leaves resource half-configured, no automatic retry).

**Better alternatives:**
- `user_data` for EC2 bootstrapping.
- `cloud-init` for richer per-instance setup.
- Pre-built AMIs (Packer).
- Configuration management (Ansible) called separately, post-Terraform.

**Acceptable provisioner use:** `local-exec` to run a one-off CLI command that has no Terraform resource yet, and the failure mode is benign (e.g., printing an output).

---

### Q24. Sensitive outputs — what does `sensitive = true` do?
Marks an output as sensitive: Terraform redacts its value from CLI output (shows `<sensitive>`). The value still ends up in the state file in plaintext.

For variables, the same flag prevents the value from appearing in plan output.

**Limitation:** doesn't actually encrypt anything. The state file remains the source of truth and must be protected by other means (encrypted backend, IAM-restricted access).

---

## E. Modules & Composition

### Q25. How do you version a module?
- **Local modules** (path source) — no versioning. Git history is the version.
- **Git modules** — `source = "git::https://...//path?ref=v1.2.0"`. The `?ref=` is your version pin.
- **Registry modules** — `source = "namespace/name/aws"` + `version = "~> 1.2"`.

**Semver discipline:** breaking changes → major version bump. Document in CHANGELOG. Consumers pin to a major version (`~> 1.0`) and upgrade deliberately.

---

### Q26. What's a module's "public API"?
Two things, only two:
1. Its **input variables** — what callers can configure.
2. Its **outputs** — what callers can read after creation.

Everything else (internal resources, locals, naming) is an implementation detail. Treat the public API like a function signature — changing it breaks callers, document changes.

**Common mistake:** modules with 50 inputs trying to be infinitely flexible. They become unusable. Keep inputs minimal and opinionated; offer escape hatches via `extra_tags` etc.

---

### Q27. How do you pass a provider into a module?
Implicit: child module inherits the default provider from the parent.

Explicit (needed when child uses an aliased provider):
```hcl
# parent
module "x" {
  source = "./x"
  providers = { aws = aws.us_east_1 }
}
# child x/main.tf
terraform {
  required_providers {
    aws = { source = "hashicorp/aws", configuration_aliases = [aws.alt] }
  }
}
```

ShopEase uses default-provider inheritance only — no cross-region resources yet.

---

### Q28. Module composition — flat vs nested?

**Flat** (recommended for most teams):
```
environments/dev/
  vpc.tf      → module.vpc (calls modules/vpc)
  eks.tf      → module.eks (calls modules/eks)
```

**Nested**:
```
modules/platform/   → calls modules/vpc + modules/eks internally
environments/dev/
  main.tf     → module.platform (one call, hides everything)
```

**Trade-off:** flat is more verbose but transparent. Nested hides complexity but creates "platform-as-a-product" where you must build a stable API for the wrapper. Choose nested only when the wrapper provides genuine abstraction (e.g., "create a service" = ECR + IAM + ALB target group + DNS — that wrapper is reusable).

---

## F. Operations & Real World

### Q29. How do you organize Terraform code in a repo?
Common layouts:

**Layout A — single repo, per-env folder (ShopEase):**
```
modules/...
environments/dev/, staging/, prod/
```
Pros: one PR sees all envs. Cons: blast radius — bad PR can touch prod.

**Layout B — separate repo per environment:**
- Pros: clean separation of permissions, prod has stricter PR rules.
- Cons: refactors that touch shared modules require multiple repos.

**Layout C — Terragrunt** — overlay tool that DRYs up per-env duplication, manages state config dynamically.

For a small team / one repo per project, Layout A is the right starting point.

---

### Q30. How does Terraform handle dependencies?
Two kinds:
- **Implicit** — Terraform infers a dependency from references. `aws_subnet.x.vpc_id = aws_vpc.y.id` makes `x` depend on `y`.
- **Explicit** — `depends_on = [aws_iam_role.example]` — use when there's a real ordering need but no attribute reference (e.g., IAM policy must exist before the role using it can be assumed).

Terraform builds a dependency **DAG** and parallelizes resource operations within the same level.

---

### Q31. What is `terraform graph`?
Outputs the dependency DAG in DOT format. Pipe to Graphviz for a visualization:
```bash
terraform graph | dot -Tpng > graph.png
```
Useful for debugging "why is Terraform waiting on this resource?" or for documenting how a stack hangs together.

---

### Q32. How do you make Terraform faster?
- **`-parallelism=N`** — increase from default 10 if you're hitting API rate limits less than parallelism (rare).
- **`-refresh=false`** on plan when you're confident state is fresh.
- **Split state** by blast radius — smaller states refresh faster.
- **Targeted plan/apply** for surgical changes (sparingly).
- **Reduce module nesting** — every module level adds overhead.

For ShopEase the cold apply is dominated by EKS cluster creation (~10 of the ~12 min). Nothing Terraform can do about AWS-side latency.

---

### Q33. How do you handle Terraform's "unknown values at plan time"?
Some attributes can't be known until apply (random IDs, auto-generated names, computed ARNs). Terraform shows them as `(known after apply)` in plan.

**Problem:** `for_each` and `count` need keys/counts known at plan time. If the count depends on something `known after apply`, you get the "value cannot be determined until apply" error.

**Workarounds:**
- Use `count` instead of `for_each` (Terraform tolerates known-after-apply for count length).
- Compute the count from inputs the user provides, not other resources' outputs.
- Two-step apply with `-target` (ugly; last resort).

ShopEase hit this in the IRSA module → resolved with `count = length(var.policy_arns)`.

---

### Q34. What's drift and how do you detect it?
**Drift** = live infrastructure has changed outside of Terraform (someone clicked in the console, another tool made a change).

**Detection:**
- `terraform plan` always refreshes — drift shows up as proposed changes.
- For continuous monitoring: scheduled `terraform plan -detailed-exitcode` in CI. Exit code 2 means drift.
- Tools: **driftctl**, **Terraform Cloud's drift detection**.

**Response options:**
1. Codify the change — update `.tf` to match.
2. Revert — apply Terraform's config.
3. `ignore_changes` if drift is intentional and tooling-driven.

---

### Q35. How do you destroy infrastructure safely?
- **Never** `terraform destroy` in prod without a paper trail. Use `-target` for surgical removal.
- **`prevent_destroy = true`** on critical resources (RDS, KMS, state bucket).
- **Removal vs destroy**: if you want to stop managing a resource but keep it in AWS, use `terraform state rm` + remove from config — not destroy.
- **Dev environments**: destroying nightly is a common cost-saving pattern. Combine with `terraform plan` in CI to detect anything that won't recreate cleanly.

---

### Q36. What's the role of provider versioning?
Pin in `required_providers`:
```hcl
aws = { source = "hashicorp/aws", version = "~> 5.0" }
```
Operators:
- `5.0` — exactly 5.0.
- `~> 5.0` — `>= 5.0, < 6.0` (allow minor/patch, block major).
- `>= 5.0, < 5.50` — explicit range.

`terraform init` writes a **`.terraform.lock.hcl`** capturing the exact resolved versions + hashes. Commit it to Git so every team member and CI gets the same providers.

Without a lock file, two engineers can have different provider versions and see different plan output.

---

### Q37. How does Terraform handle secret rotation?
Terraform isn't designed for rotation. Rotation is a runtime concern (secret values change frequently, infrastructure doesn't).

**Pattern:** Terraform creates the **container** for the secret (the AWS Secrets Manager entry, the IAM permissions, the IRSA role). The **value** is either:
- Set once at creation and never touched by Terraform again (`ignore_changes = [secret_string]`).
- Rotated by AWS-native automation (Lambda) outside Terraform.
- Read by apps via External Secrets Operator / SDK, not by Terraform.

ShopEase: Terraform creates 4 Secrets Manager entries; values are set initially and rotated through AWS / ESO afterwards.

---

### Q38. What's the difference between Terraform OSS, Cloud, and Enterprise?
- **OSS** (free, what most teams use) — the CLI.
- **Terraform Cloud** (SaaS) — remote state, remote runs, web UI, free tier for small teams.
- **Terraform Enterprise** — self-hosted version of Cloud for regulated industries.
- **OpenTofu** — open-source fork of Terraform after the BSL license change. Drop-in compatible with TF 1.5.x, diverging since.

For most projects OSS + a CI runner is sufficient. Adopt Cloud/Enterprise when you need policy-as-code (Sentinel), private module registry, or governance features.

---

### Q39. How do you implement policy-as-code for Terraform?
- **Sentinel** (Terraform Enterprise/Cloud) — Hashicorp's policy engine. Hooks into plan to enforce rules ("no public S3 buckets", "EC2 must have specific tags").
- **OPA / conftest** — open-source. Evaluate JSON plan output (`terraform show -json plan.tfplan`) against Rego policies.
- **Checkov / tfsec / Terrascan** — static analyzers that scan `.tf` files for known anti-patterns / CIS benchmarks.

CI gate: plan → policy check → human approval → apply. Failing policies block the apply.

---

### Q40. Resource targeting (`-target`) — when is it OK?
`terraform apply -target=aws_instance.x` applies only that resource and its dependencies.

**OK uses:**
- Incident response — fix one broken resource without waiting for full plan.
- Breaking dependency cycles in initial bootstrap.

**Not OK:**
- Routine use — defeats Terraform's holistic plan. Hides side effects.
- Avoiding fixing real plan errors.

Hashicorp's docs explicitly call `-target` "a last resort". Production-grade teams use it rarely and document each occurrence.

---

### Q41. What's `terraform refresh`?
Reads current state from cloud APIs and updates the state file to match (without making any cloud changes). Mostly invoked implicitly at the start of every plan/apply. The standalone `terraform refresh` command was deprecated in favor of `terraform apply -refresh-only` — which shows a plan of state-only updates and asks for approval.

Useful when you suspect state is stale and want to fix it without running a full plan.

---

### Q42. Final question — what's the most expensive Terraform mistake you've seen / made?
Be ready with a story. Acceptable answers:

- **Destroyed prod state file** — recoverable via S3 versioning if you set it up.
- **`terraform destroy` in prod by mistake** — `prevent_destroy` is your seatbelt.
- **State drift caused by manual change to a load balancer** — Terraform reverted it and dropped traffic for 30 s.
- **Renamed a module without `moved` block** — replaced 20 stateful resources.
- **(ShopEase's story)**: about to destroy a live KMS key during a module refactor; caught it in plan review and recovered with `state rm` + `import`.

The right thing to convey isn't *"I never make mistakes"*, it's *"I caught it because I always read the plan, and I knew the recovery primitives (`state`, `import`, `moved`)."*
