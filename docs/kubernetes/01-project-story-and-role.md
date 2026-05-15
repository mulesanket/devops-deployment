# ShopEase on EKS — Kubernetes Project Story & My Role

> **Target audience:** DevOps Engineer interviews, 3–3.5 YOE
> **Use this doc to:** Tell a clean 3-minute project story, then deep-dive on any layer.

---

## 1. The 60-Second Elevator Pitch

> *"ShopEase is a Spring Boot microservices e-commerce platform running on **Amazon EKS** in `ap-south-1`. It has four backend services — auth, product, cart, order — each independently scalable, plus a React SPA on S3/CloudFront, an Aurora MySQL data layer, and an asynchronous signup-email pipeline using SNS → SQS → Lambda → SES. The whole platform is provisioned via Terraform modules and deployed to Kubernetes with production-grade patterns: HPA, PDB, PriorityClass, topology spread, hardened security contexts, IRSA for AWS access, and AWS Secrets Manager integration through External Secrets Operator. My role was DevOps Engineer — I owned the infrastructure-as-code, the cluster, the deployment pipeline, and the platform hardening."*

---

## 2. The "Why" — Problem Statement

The development team had a working Spring Boot codebase but no production deployment pattern. The asks were:

1. Run microservices in containers with **independent scaling** per service.
2. Achieve **zero-downtime deployments** during business hours.
3. Provide **AZ-level fault tolerance** (Mumbai region has 3 AZs).
4. Stop committing **plaintext secrets** to Git (compliance requirement).
5. Provide a **repeatable, codified** environment (dev today, prod tomorrow).
6. Run on AWS, keep cost reasonable for non-prod (≤ $200/month dev).

That maps cleanly onto EKS + Terraform + a hardened K8s manifest set.

---

## 3. High-Level Architecture

```
                     ┌─────────────────────────┐
   end users ───────►│ CloudFront + S3 (React) │
                     └─────────────┬───────────┘
                                   │ /api/*
                                   ▼
                     ┌─────────────────────────┐
                     │ AWS ALB (Ingress)       │  path-based routing
                     └─────┬────────┬──────┬───┘
                           │        │      │
                  ┌────────▼──┐ ┌───▼──┐ ┌─▼────┐ ┌──────┐
                  │   auth    │ │ prod │ │ cart │ │order │   Deployments
                  └─────┬─────┘ └──┬───┘ └──┬───┘ └──┬───┘   (HPA 2–6 each)
                        │          │        │        │
                        └──────────┴────────┴────────┘
                                   │
                                   ▼
                       Aurora MySQL (RDS, Multi-AZ)
                                   │
   auth ─signup─► SNS ─► SQS ─► Lambda ─► SES (welcome email)

   Secrets:  AWS Secrets Manager + KMS CMK ◄── ESO (IRSA) ──► in-cluster Secrets
```

**Cluster topology:**
- EKS 1.30, managed node group of 2× `t3.medium` (autoscaling 2→4)
- 3 AZs (`ap-south-1a/b/c`), private node subnets, NAT per AZ
- VPC CNI + CoreDNS + kube-proxy as managed add-ons
- One application namespace per environment: `shopease-webapp-development`

---

## 4. What I Personally Built (Talking Points)

### 4.1 Infrastructure-as-Code (Terraform)
- **Modular layout**: `modules/{vpc, eks, rds, ecr, irsa, kms, secrets-manager, sns, sqs, lambda, s3-frontend, cloudfront, ses}`
- **Per-environment composition**: `environments/development/` with capability-split files (`network.tf`, `compute.tf`, `data.tf`, `messaging.tf`, `external-secrets.tf`, etc.) — *not* one giant `main.tf`. This is the enterprise pattern.
- **Remote state**: S3 backend with DynamoDB lock table.
- **Reusable IRSA module** taking `oidc_provider_arn`, `namespace`, `service_account_name`, `policy_arns[]` → emits a role with the correct trust policy. Used twice (auth-service, ESO).

### 4.2 Kubernetes Deployment Hardening
Every service Deployment carries:

| Concern | Implementation |
|---|---|
| Auto-scale on CPU/memory | `HorizontalPodAutoscaler` 2→6 replicas |
| Voluntary-disruption safety | `PodDisruptionBudget` (`minAvailable: 1`) — node drains can't take a service down |
| Scheduling priority | `PriorityClass: shopease-critical` |
| AZ + host spread | `topologySpreadConstraints` (`maxSkew: 1` zone + hostname) |
| Rolling deploys | `maxUnavailable: 0, maxSurge: 1`, `minReadySeconds: 10` |
| Container security | `runAsNonRoot`, `runAsUser: 10001`, `readOnlyRootFilesystem`, `allowPrivilegeEscalation: false`, `seccompProfile: RuntimeDefault`, dropped `ALL` capabilities |
| Resource isolation | `requests` + `limits` on CPU/memory tuned per service |
| Health gating | `startupProbe`, `livenessProbe`, `readinessProbe` separated, all hitting Spring Boot Actuator |
| Graceful shutdown | `terminationGracePeriodSeconds: 60` matches Spring `shutdown=graceful` |
| Service identity | Dedicated `ServiceAccount` per workload |

### 4.3 12-Factor Config Externalization
- Non-sensitive config → `ConfigMap` per service, mounted via `envFrom`
- Sensitive values → `Secret` per service, mounted via `envFrom` (initially plaintext, then migrated to ESO — see §4.5)
- Image tag pinned (`:1.1.0`, `imagePullPolicy: IfNotPresent`)
- ECR repos set to `IMMUTABLE` so tags can't be silently overwritten

### 4.4 IRSA (IAM Roles for Service Accounts)
- **Auth-service** needs to publish to SNS on signup. Instead of baking AWS keys into the image:
  - Cluster has an OIDC provider already linked to IAM.
  - Terraform creates an IAM role with a trust policy scoped to `system:serviceaccount:shopease-webapp-development:auth-service-sa`.
  - The K8s SA carries the annotation `eks.amazonaws.com/role-arn: <arn>`.
  - AWS SDK inside the pod auto-assumes the role via projected SA token. **Zero static credentials anywhere.**

### 4.5 Secrets Refactor (the big one)
**Before:** plaintext `secret.yaml` committed to Git with DB password and JWT signing key.
**After (pipeline):**

```
   Terraform                  AWS                      EKS Cluster
 ─────────────────         ─────────────         ────────────────────
 modules/secrets-manager ─► 4 SecretsManager   ◄── ESO controller pod
 modules/kms             ─► customer-managed       (running in ns
                            CMK + alias            external-secrets,
                                                   IRSA-authenticated)
                                                       │
                                                       ▼
                                            ClusterSecretStore (1)
                                                       │
                                                       ▼
                                            4× ExternalSecret CRs
                                                       │
                                                       ▼
                                            4× native K8s Secrets
                                            (auto-refreshed hourly)
                                                       │
                                                       ▼
                                            Deployments (envFrom)
```

**Components I built:**
1. KMS CMK + alias (Terraform module) — customer-managed key, not AWS-default.
2. 4 secrets in AWS Secrets Manager (one per service), encrypted with that CMK. DB password reused from Terraform variable; JWT secret generated by `random_password`.
3. IAM policy granting `secretsmanager:GetSecretValue|DescribeSecret` on `shopease/${env}/*` + `kms:Decrypt` on the CMK only — least privilege.
4. IRSA role for ESO bound to SA `external-secrets/external-secrets`.
5. ESO installed via Terraform's `helm_release` (chart `external-secrets/external-secrets` v0.10.4, IRSA role injected through `set` block).
6. `ClusterSecretStore` (cluster-wide AWS connection, `jwt` auth via ESO's SA).
7. Per-service `ExternalSecret` with `dataFrom.extract` — pulls the whole JSON and materializes a K8s Secret with the same name the deployments already reference. **App code unchanged.**
8. Deleted plaintext `secret.yaml` files, added `.gitignore` patterns to prevent recurrence.

**Outcome:** rotation flow is now "update value in AWS → ESO syncs within `refreshInterval: 1h` → rolling restart picks it up." No Git, no PR, no plaintext.

---

## 5. Numbers Worth Remembering

| Metric | Value |
|---|---|
| Services | 4 backend + 1 SPA |
| Cluster nodes | 2 (autoscale 2→4) `t3.medium` |
| Pod replicas per svc | 2 baseline, HPA up to 6 |
| Availability zones | 3 |
| AWS Secrets | 4, encrypted with 1 CMK |
| ESO refresh interval | 1 hour |
| Deployment strategy | RollingUpdate, `maxUnavailable: 0` |
| Image tag | Immutable in ECR, pinned in YAML |

---

## 6. "Tell me about a challenge" Stories (STAR-style, ready to use)

### Story A — KMS Refactor Disaster Recovery
- **Situation:** Refactored an inline KMS resource into a Terraform module after secrets were already encrypted with the old key.
- **Task:** The apply showed "destroy old key + create new key" — would have made all secrets unrecoverable.
- **Action:** Cancelled the in-progress key deletion (`aws kms cancel-key-deletion`), removed the orphan from Terraform state (`terraform state rm`), imported the existing key into the new module path (`terraform import`), then re-pointed the alias to confirm continuity.
- **Result:** Zero data loss, zero downtime. Lesson: never refactor stateful crypto modules without first inspecting the plan diff for `destroy` actions on `aws_kms_key`.

### Story B — Choosing ESO Over Pod-Side Secrets Manager SDK
- **Situation:** Team considered two patterns to consume AWS Secrets Manager — (a) each app reads at startup via AWS SDK, (b) operator-based sync (ESO).
- **Task:** Recommend one and justify.
- **Action:** Chose ESO because (1) apps stay Kubernetes-native — they read `envFrom: secretRef`, no AWS coupling in code; (2) one IAM role for ESO instead of per-service SDK roles + boilerplate; (3) rotation is centralized; (4) audit trail in K8s + CloudTrail. Trade-off accepted: a 1-hour refresh window, mitigated by triggering a rollout on rotation.
- **Result:** Clean separation — app teams own their services, platform team owns secret delivery.

### Story C — Helm Provider Auth Failure on Windows
- **Situation:** First `terraform apply` of the ESO Helm release failed with "Kubernetes cluster unreachable — json parse error".
- **Task:** Diagnose without touching the cluster.
- **Action:** Ran `aws eks get-token` manually — discovered output came back as an ASCII table because my AWS CLI default output was `table`, not `json`. The exec auth plugin couldn't parse it.
- **Action 2:** Fixed at the source — added `--output json` explicitly to the provider's exec args (not as a global config change), so the behaviour is reproducible across machines.
- **Result:** Apply succeeded. Lesson: when an exec-plugin chain breaks, run the leaf command in isolation first — don't guess.

---

## 7. What I'd Do Next (shows growth mindset)

When asked "what would you improve?" — these answers signal seniority:

1. **TLS everywhere** — ACM cert on the ALB ingress, currently HTTP only.
2. **NetworkPolicy** — default-deny pod-to-pod, explicitly allow auth↔db, etc.
3. **Kustomize overlays** — base + dev/prod overlays to eliminate per-env YAML duplication.
4. **Observability stack** — Prometheus + Grafana + Loki via Helm, ServiceMonitor per workload, Spring Actuator `/actuator/prometheus` already exposed.
5. **GitOps** — ArgoCD watching `deployment-kubernetes/`, ESO + cluster add-ons stay in Terraform, app workloads move to ArgoCD.
6. **Policy-as-code** — Kyverno or OPA Gatekeeper to enforce `runAsNonRoot`, image registry allowlist, mandatory resource limits cluster-wide.
7. **Secret rotation automation** — Lambda triggered by Secrets Manager rotation → patch deployment annotation → triggers rolling restart automatically (no manual `kubectl rollout restart`).

---

## 8. My Role Statement (1-liner for résumé / opening answer)

> *"I was the DevOps engineer on ShopEase — I owned end-to-end infrastructure on AWS via Terraform, the EKS cluster and all Kubernetes manifests including the production hardening patterns, the IRSA-based AWS access model, and the migration from plaintext secrets to AWS Secrets Manager via External Secrets Operator."*
