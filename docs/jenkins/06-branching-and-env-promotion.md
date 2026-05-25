# 06 — Branching strategy & environment promotion

> Interview-grade reference for "How did you handle pipelines across
> environments?" and "What was your Git branching model?"

This doc captures the **branching model**, **how code/artifacts flow
between environments**, and **how the same CD pipeline targets
dev/qa/stage/prod**.

---

## TL;DR (the sentence to lead with)

> **One pipeline definition per service, parameterized by environment.
> One immutable image (tagged with the git SHA), promoted across
> environments by re-running the same CD job against a different
> target. Auto-deploy to dev; manual + approval-gated for stage and
> prod.**

---

## 1. Branching model — GitFlow-lite

Five branch *kinds*, each with a clear owner and a clear environment.

| Branch          | Owner                | Environment | CI? | CD?                                |
| --------------- | -------------------- | ----------- | --- | ---------------------------------- |
| `feature/*`     | developer            | none        | ✅  | ❌ (build & test only)            |
| `development`   | developers (via PR)  | **dev**     | ✅  | ✅ auto on merge                   |
| `qa`            | release engineer     | **qa**      | ✅  | ✅ auto on merge                   |
| `release/x.y.z` | release engineer     | **stage**   | ✅  | ✅ auto on branch create / push    |
| `master`        | release engineer     | **prod**    | ✅  | ⚠️ manual + `input` approval gate |

`hotfix/*` branches off `master` for emergency prod fixes; same merge
rules as `release/*` but on a compressed timeline.

---

## 2. The flow — one feature, end-to-end

```
  feature/login-fix                        (dev local; CI on push)
        │
        │  PR (peer review)
        ▼
  development  ─── CI ──► CD ──► DEV cluster              (auto)
        │
        │  PR (release lead, batched — release train)
        ▼
  qa           ─── CI ──► CD ──► QA cluster               (auto)
        │
        │  git checkout -b release/1.4.0   (release engineer)
        │  git tag v1.4.0-rc1
        ▼
  release/1.4.0 ─ CI ──► CD ──► STAGE cluster             (auto)
        │
        │  PR after UAT sign-off
        ▼
  master       ─── CI ──► CD ──► PROD cluster             (MANUAL + APPROVAL)
        │  tag v1.4.0 on the merge commit
        │
        │  back-merge master → development, qa, release/*
        ▼
  development  (sync — hotfixes don't get lost)
```

### Rules of the road

1. **Devs never push to `qa`, `release/*`, or `master`.** Those are
   release-engineer-owned.
2. **Promotion = merge forward, never cherry-pick backward.** Backward
   movement is reserved for hotfix back-merges.
3. **Tags are immutable.** `v1.4.0` is the exact commit that went to
   prod. Used for rollbacks.
4. **Hotfixes**: `master` → `hotfix/x.y.z+1` → `master`, then back-merge
   into `development`, `qa`, and any open `release/*` so the fix
   propagates.

---

## 3. Why this matches our CD pipeline

Our `Jenkinsfile.cd` is **environment-parameterized**:

```groovy
parameters {
    choice(name: 'ENVIRONMENT', choices: ['development', 'staging', 'production'])
    string(name: 'GIT_SHA',     defaultValue: '', description: 'blank = use S3 latest pointer')
    booleanParam(name: 'DRY_RUN', defaultValue: false)
}

environment {
    CLUSTER_NAME = "shopease-webapp-${params.ENVIRONMENT == 'production' ? 'prod' :
                                       params.ENVIRONMENT == 'staging'    ? 'stage' :
                                       params.ENVIRONMENT == 'qa'         ? 'qa'    : 'dev'}"
    NAMESPACE    = "shopease-webapp-${params.ENVIRONMENT}"
    S3_BUCKET    = "shopease-webapp-${params.ENVIRONMENT}-ci-artifacts"
}
```

A single Jenkinsfile drives all four environments. No per-env Jenkinsfile
duplication; no env-specific code branches inside the pipeline.

### Trigger matrix

| Source branch        | CD trigger                                  | Target env  |
| -------------------- | ------------------------------------------- | ----------- |
| `development`        | CI's `Trigger CD` stage (auto, build step)  | dev         |
| `qa`                 | CI's `Trigger CD` stage (auto)              | qa          |
| `release/x.y.z`      | CI's `Trigger CD` stage (auto)              | stage       |
| `master`             | **Manual** — operator clicks "Build with    | prod        |
|                      | Parameters", picks `ENVIRONMENT=production`,|             |
|                      | pipeline pauses on `input` approval         |             |

---

## 4. The artifact = the SHA. Build once, deploy many.

CI builds **once per branch commit** and pushes
`…/<service>:<gitSha>` to ECR. CD **never rebuilds** for promotion — it
re-applies the same image SHA against a different cluster.

```
        ┌─► dev cluster      (image:503e039)
        │
ECR     ├─► qa cluster       (image:503e039)   same digest
:503e039│
        ├─► stage cluster    (image:503e039)   same digest
        │
        └─► prod cluster     (image:503e039)   same digest
```

This guarantees **what QA tested is bit-for-bit what hits prod**. No
"works in stage, fails in prod" because of a rebuild between envs.

### S3 pointer convention (per env, per service)

```
s3://shopease-webapp-<env>-ci-artifacts/<service>/
    ├── latest/<branch>.json        ← written by CI on success
    └── deployed/<branch>.json      ← written by CD on success
```

- `latest/` answers: *"what's the newest image built from this branch?"*
- `deployed/` answers: *"what's actually running in this environment?"*
- Drift detector: if `latest != deployed` in any env, something's
  pending promotion or a deploy failed silently.

---

## 5. Production approval gate

Inside `Jenkinsfile.cd`:

```groovy
stage('Production approval') {
    when { expression { params.ENVIRONMENT == 'production' } }
    steps {
        timeout(time: 30, unit: 'MINUTES') {
            input message: "Deploy ${env.GIT_SHA} to PRODUCTION?",
                  submitter: 'mule-sanket,release-managers'
        }
    }
}
```

Layered controls:

1. **Default param** = `development` — accidental clicks land in dev.
2. **Jenkins folder RBAC** — only `release-managers` group has `Build`
   on the `*-cd` job; everyone else is `Read`.
3. **`input` step** — pauses the pipeline; only listed `submitter`s can
   approve; 30-minute timeout auto-aborts.
4. **Namespaced K8s RBAC** — even if someone bypassed the gate, the
   IRSA role can only `patch deployments` in the target namespace, not
   cross-namespace or cluster-scoped.

---

## 6. Per-environment isolation

Each environment is **physically separate**:

| Resource             | dev                                  | qa                                | stage                                  | prod                                 |
| -------------------- | ------------------------------------ | --------------------------------- | -------------------------------------- | ------------------------------------ |
| EKS cluster          | `shopease-webapp-dev`                | `shopease-webapp-qa`              | `shopease-webapp-stage`                | `shopease-webapp-prod`               |
| Namespace            | `shopease-webapp-development`        | `shopease-webapp-qa`              | `shopease-webapp-staging`              | `shopease-webapp-production`         |
| IRSA role            | `…-development-ci-agent-irsa`        | `…-qa-ci-agent-irsa`              | `…-staging-ci-agent-irsa`              | `…-production-ci-agent-irsa`         |
| S3 artifact bucket   | `shopease-webapp-development-ci-…`   | `shopease-webapp-qa-ci-…`         | `shopease-webapp-staging-ci-…`         | `shopease-webapp-production-ci-…`    |
| Secrets Manager path | `/shopease/development/*`            | `/shopease/qa/*`                  | `/shopease/staging/*`                  | `/shopease/production/*`             |
| Terraform workspace  | `environments/development/`          | `environments/qa/`                | `environments/staging/`                | `environments/production/`           |

Same Terraform modules, different `*.tfvars` per env. No shared
credentials between envs — a compromise in dev cannot escalate to prod.

---

## 7. Per-environment configuration (ConfigMaps / Secrets)

| Layer        | Mechanism                                                                      |
| ------------ | ------------------------------------------------------------------------------ |
| Image        | **identical SHA across envs** — zero per-env build artifacts                  |
| Static config| `deployment-kubernetes/<service>/configmap.yaml` — values differ per env via Kustomize overlays |
| Secrets      | `ExternalSecret` → AWS Secrets Manager (per-env prefix, per-env ClusterSecretStore) |
| Resources    | HPA `min/max` and `Deployment.resources` differ per env (more headroom in prod) |

Nothing env-specific is **inside the image**. The image is a pure
artifact; the cluster injects environment context at runtime.

---

## 8. What if the interviewer asks about trunk-based development?

> *"GitFlow-lite was the right fit for this product because QA was a
> manual, gated cycle. **Trunk-based development** with feature flags
> and progressive rollout (canary → 1% → 10% → 100%) is the modern
> alternative for fast-moving SaaS teams — single `main` branch, deploy
> on every merge, feature gates instead of branch gates. It assumes
> mature feature-flag infra and a strong automated UAT suite. For a
> Java microservices product with human UAT and a release-train
> cadence, GitFlow-lite is the pragmatic choice."*

Mentioning both models — and explaining *why* you picked one — signals
seniority.

---

## 9. What I actually built vs. what I would extend

| Implemented today                              | Designed but not provisioned             |
| ---------------------------------------------- | ---------------------------------------- |
| `development` branch → dev EKS, auto CD        | `qa`, `release/*`, `master` branches     |
| `Jenkinsfile.cd` with `ENVIRONMENT` parameter  | qa/stage/prod EKS clusters               |
| IRSA + Access Entry + namespaced RBAC for dev  | Same pattern replicated per-env via TF   |
| S3 `latest/` + `deployed/` pointers for dev    | Same buckets per env                     |
| CI's `Trigger CD` stage (push-based CD)        | `input` approval gate for prod           |

**Honest interview line**: *"I implemented the dev environment
end-to-end. The pipeline and IaC are deliberately parameterized so
adding qa/stage/prod is a `terraform apply` per environment plus a
Jenkins job parameter change — no Jenkinsfile rewrite, no per-env
code."*

---

## 10. Common interview follow-ups (with one-liner answers)

| Question | Answer |
|---|---|
| "How does QA know what to test?" | Release lead opens `development → qa` PR with all Jira tickets in the description; CD auto-deploys; QA tests against the qa cluster URL. |
| "Can a dev hotfix prod directly?" | No. `hotfix/*` branches from `master`, PR back to `master`, then back-merge to `development`/`qa`/`release/*`. RBAC prevents direct push to `master`. |
| "What if stage UAT fails?" | Fix on the release branch (`release/1.4.0`), tag `v1.4.0-rc2`, redeploy to stage. Never edit `master`. |
| "How do you roll back prod?" | Re-run `Jenkinsfile.cd` with `ENVIRONMENT=production` and `GIT_SHA=<previous-good-sha>` (read from `deployed/master.json` before the bad deploy). Same image promotion mechanism, in reverse. |
| "Why not auto-deploy to prod?" | Risk management. Production rollouts are a business event, not a technical one — require a human signal that change windows, on-call coverage, and customer comms are aligned. |
| "Why not GitOps (ArgoCD)?" | Push-based CD was a deliberate choice for this project: Jenkins already owned the CI half, the team had Jenkins expertise, and the approval-gate / RBAC story is simpler to reason about when one tool owns the whole pipeline. ArgoCD is a great fit when the cluster fleet grows or multiple teams own different services in the same cluster. |

---

## 11. Diagram for whiteboard interviews

```
                        ┌──────────────────────┐
   feature/*  ─PR──►    │   development        │  ──► dev cluster   (auto)
                        └──────────┬───────────┘
                                   │ PR (release train)
                                   ▼
                        ┌──────────────────────┐
                        │   qa                 │  ──► qa cluster    (auto)
                        └──────────┬───────────┘
                                   │ git branch release/x.y.z
                                   ▼
                        ┌──────────────────────┐
                        │   release/x.y.z      │  ──► stage cluster (auto)
                        └──────────┬───────────┘
                                   │ PR after UAT
                                   ▼
                        ┌──────────────────────┐
                        │   master  (tagged)   │  ──► prod cluster  (MANUAL + APPROVAL)
                        └──────────────────────┘

   ECR image :<gitSha>  ────────► same digest deployed to every env
```

---

*Last updated: 2026-05-25*
