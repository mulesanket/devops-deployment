# 05 — CD Strategy

> Author: Sanket Mule
> Scope: How Shopease promotes a CI-built container image into the
> `shopease-webapp-development` EKS namespace.

---

## 1. TL;DR

| Aspect              | Decision                                                                     |
| ------------------- | ---------------------------------------------------------------------------- |
| Style               | **Push-based CD** from Jenkins (not GitOps / Argo CD)                        |
| Job layout          | **Separate `Jenkinsfile.cd`** per service (not stages inside CI)             |
| Image substitution  | **`sed`** on a checked-in `deployment.yaml` with `IMAGE_TAG_PLACEHOLDER`     |
| Trigger             | **Auto** from CI (`development`/`main` only) + **manual** with optional SHA  |
| Agent pod           | Lean 2-container pod (`aws-cli` + `kubectl`) via `shopeaseDeployer()`        |
| Auth to EKS         | **IRSA + EKS Access Entry** mapped to K8s group `shopease-deployers`         |
| Cluster RBAC        | Namespaced `Role` in `shopease-webapp-development`, no secrets/SA write      |
| Rollback            | Re-run CD with `GIT_SHA` = older 7-char SHA                                  |
| Image source        | Immutable ECR tag `:<gitSha>` (built and scanned by CI)                      |

---

## 2. Why these decisions

### 2.1 Push CD over GitOps (Argo CD / Flux)

Argo CD is the right answer at scale (10+ services, multi-tenant clusters,
audit-heavy environments). For a 4-service development cluster owned by
one team, it adds a second control loop, a second source of truth (`apps`
repo vs `deployment-kubernetes/`), and a second failure mode to debug.
Push CD from Jenkins keeps the pipeline as the single observable timeline:
one job run = one deployment attempt = one log to read.

We can revisit this when:

- We add a second cluster (staging / prod) and want diff-based promotion
- The SRE team starts caring about drift detection between git and live state
- Per-app sync windows / progressive delivery (Argo Rollouts) become a need

### 2.2 Separate `Jenkinsfile.cd`, not stages in CI

Mixing build + deploy in one pipeline ties their lifetimes together:

- A manual redeploy (rollback, env-var bump) would have to re-run the
  whole CI chain — including Trivy and Maven — just to flip an image tag.
- A CI failure deep in scan stages would leave you unable to deploy a
  previously-green SHA without skipping stages.
- The pod template needed for CI (Maven, Kaniko, Trivy ~3.5 CPU) is
  wasteful for a 30-second `kubectl apply`.

Two jobs, two pod templates (`shopeaseAgent` vs `shopeaseDeployer`),
two timelines. CI's contract ends at "image in ECR + manifest in S3".

### 2.3 `sed` over Kustomize / Helm

The substitution is one line: `image: …:IMAGE_TAG_PLACEHOLDER`. Adding
Kustomize means a `kustomization.yaml` per service, an overlays directory
tree, and a new mental model for new joiners. We don't yet have the
multi-env overlay use case Kustomize is built for. When we do (staging
/ prod), the migration is:

1. Replace `sed` with `kustomize edit set image …`
2. Add `overlays/{development,staging,production}/`
3. Keep the same trigger / RBAC scaffolding from this doc

Until then, `sed` keeps the manifest plain YAML — viewable, diffable,
greppable.

### 2.4 Target namespace is `shopease-webapp-development`, not `default`

Application workloads must never live in `default`. Namespacing gives us:

- Isolated RBAC (CD pipeline can't touch other teams' workloads)
- Isolated `ResourceQuota` / `LimitRange` (later)
- Clean uninstall (drop the namespace, drop the app)
- Honest blast radius accounting in incident reviews

---

## 3. Architecture

```
GitHub push (development branch)
       │
       ▼
┌──────────────────────────┐
│  CI Job (Jenkinsfile)    │  agent: shopeaseAgent()
│  build → scan → push     │  --> ECR :<gitSha>  (immutable)
│  publish → trigger CD    │  --> S3 latest/development.json
└─────────────┬────────────┘
              │ build job: auth-service-cd
              │ params: GIT_SHA=<sha>
              ▼
┌──────────────────────────┐
│  CD Job (Jenkinsfile.cd) │  agent: shopeaseDeployer()
│  resolve → verify ECR    │  IRSA: eks:DescribeCluster
│  render → apply          │  RBAC: shopease-deployer Role
│  rollout → verify        │  Namespace: shopease-webapp-development
│  tag S3 as deployed      │  S3 deployed/development.json
└──────────────────────────┘
```

---

## 4. Auth chain (the part everyone gets wrong)

There are **two** authorization layers, and both must pass:

| Layer  | What                       | Source of truth                                     | Grants                                              |
| ------ | -------------------------- | --------------------------------------------------- | --------------------------------------------------- |
| AWS    | IAM policy on IRSA role    | `infrastructure-terraform/.../ci-cd.tf`             | `eks:DescribeCluster` (to fetch kubeconfig)         |
| K8s    | EKS Access Entry → Group   | `aws_eks_access_entry` in same `ci-cd.tf`           | `shopease-deployers` group identity in the cluster  |
| K8s    | Role + RoleBinding         | `deployment-kubernetes/base/ci-cd-jenkins-deployer-rbac.yaml` | `get/patch deployments`, no `secrets`     |

Flow inside the deployer pod:

1. K8s mounts a projected SA token for `jenkins-agent-builder`.
2. `aws sts assume-role-with-web-identity` (done automatically by SDK)
   returns short-lived AWS creds for `ci-agent-irsa`.
3. `aws eks update-kubeconfig` calls `DescribeCluster` → writes
   `/root/.kube/config` with `aws eks get-token` as the auth exec plugin.
4. `kubectl` calls EKS, which sees IAM role `ci-agent-irsa`, looks it up
   in the **Access Entries** table, maps it to group `shopease-deployers`.
5. K8s authorizer checks the `RoleBinding` in `shopease-webapp-development`
   → grants the verbs in the `Role`.

Common breakages and what they look like:

- `403 forbidden` calling `DescribeCluster` → IAM policy missing the `eks:*` statement.
- `error: You must be logged in to the server (Unauthorized)` from kubectl → Access Entry not created, or `authentication_mode` still `CONFIG_MAP` only.
- `403 forbidden` on `deployments.apps` → RoleBinding subject doesn't match the group on the Access Entry.

---

## 5. Trigger model

### 5.1 Auto-promote on green CI

The CI pipeline's last stage is `Trigger CD`, gated by:

```groovy
when { anyOf { branch 'development'; branch 'main' } }
```

It calls `build job: 'auth-service-cd'` with `GIT_SHA` and `wait: false,
propagate: false`. The CD job appears in Jenkins as an independent build
with its own log, its own SCM checkout, and its own success/failure.

### 5.2 Manual rollback / re-deploy

`auth-service-cd → Build with Parameters`:

- Leave `GIT_SHA` blank → deploys whatever is in
  `s3://…/auth-service/latest/development.json` (i.e. last successful CI build).
- Type a SHA → deploys that exact image (rollback or pin).
- Check `DRY_RUN` → renders the manifest, runs `kubectl diff`, skips
  the `apply`. Useful for previewing what an SRE-initiated rollback would change.

---

## 6. Rollback recipe

1. Open the CD job for the service (e.g. `auth-service-cd`).
2. Click **Build with Parameters**.
3. Paste the previous green SHA into `GIT_SHA` (find it in the prior
   build's `BUILD SUMMARY` log, or `aws s3 ls s3://…/auth-service/manifests/`).
4. Run. The rollout uses the same path as a forward deploy — there's
   nothing special about "rolling back" because ECR tags are immutable
   and addressable by SHA.

The S3 `deployed/<env>.json` pointer is updated to reflect the rolled-back
SHA, so the next "blank GIT_SHA" deploy won't accidentally undo the rollback.

---

## 7. What this pipeline does NOT do (yet)

| Gap                                  | Why deferred                                              | When to add                          |
| ------------------------------------ | --------------------------------------------------------- | ------------------------------------ |
| Auto-rollback on failed rollout      | Rolling update with `maxUnavailable=0` already preserves prior RS; explicit rollback hides root cause | When MTTR matters more than RCA |
| Smoke tests after rollout            | `/health` is already gated by readinessProbe              | Add when external dependencies (DB migrations) need post-deploy validation |
| Slack / Teams notifications          | Jenkins email + build status badge is sufficient for one team | When > 1 team consumes the dev env |
| Multi-env promotion (dev → stg → prd)| Only one cluster exists today                             | When staging cluster is provisioned  |
| Cosign image signing + verify        | Trivy scan + immutable tag is the current trust boundary  | Before any production rollout        |
| Argo Rollouts (canary, blue/green)   | Service is internal-only and has no SLO yet               | When public traffic + SLOs land      |

---

## 8. Files touched for this work

| File                                                                                    | Purpose                                          |
| --------------------------------------------------------------------------------------- | ------------------------------------------------ |
| `jenkins-library/vars/shopeaseDeployer.groovy`                                          | Lean CD agent pod (aws-cli + kubectl)            |
| `application-backend/auth-service/Jenkinsfile.cd`                                       | 9-stage CD pipeline for auth-service             |
| `application-backend/auth-service/Jenkinsfile`                                          | Added `Trigger CD` stage                         |
| `deployment-kubernetes/auth-service/deployment.yaml`                                    | `:1.1.0` → `:IMAGE_TAG_PLACEHOLDER`              |
| `deployment-kubernetes/base/ci-cd-jenkins-deployer-rbac.yaml`                           | Role + RoleBinding for `shopease-deployers`      |
| `infrastructure-terraform/environments/development/ci-cd.tf`                            | + `eks:DescribeCluster` IAM, + EKS Access Entry  |
| `infrastructure-terraform/modules/eks/main.tf`                                          | Enable `API_AND_CONFIG_MAP` auth mode            |

---

## 9. One-time Jenkins job setup

For each service, create a second Jenkins Pipeline job alongside the CI job:

| Setting              | Value                                                        |
| -------------------- | ------------------------------------------------------------ |
| Job name             | `auth-service-cd` (must match `CD_JOB_NAME` in CI)           |
| Type                 | Pipeline (not multibranch — CD is single-env per job)        |
| Pipeline definition  | Pipeline script from SCM                                     |
| SCM                  | Same repo as CI                                              |
| Branch               | `development`                                                |
| Script Path          | `application-backend/auth-service/Jenkinsfile.cd`            |
| Triggers             | None (triggered by CI via `build job:`)                      |
| Parameters           | Auto-discovered from `Jenkinsfile.cd` after first run        |

Same pattern repeated for cart-service, order-service, product-service
once their CI pipelines are migrated.
