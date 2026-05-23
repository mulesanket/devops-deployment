# ShopEase Jenkins CI/CD on EKS — Project Story & My Role

> **Target audience:** DevOps Engineer interviews, 3–3.5 YOE
> **Use this doc to:** Tell a clean 3-minute pipeline story, then deep-dive on any stage, container, or auth mechanism.

---

## 1. The 60-Second Elevator Pitch

> *"ShopEase has four Spring Boot microservices that all needed a hardened, secure, reproducible CI/CD pipeline. We had Jenkins on EC2 already, but the legacy pipeline ran every command on the Jenkins controller — docker daemon mounted, AWS keys baked in, host drift, no isolation. I migrated the pipeline so the controller only **orchestrates** and every build runs on an **ephemeral pod inside our EKS cluster** in the `jenkins-cicd-agents` namespace. Each build pod has five containers — jnlp, maven, kaniko, aws-cli, trivy — all sharing one workspace volume. AWS auth uses **IRSA** so there are no long-lived keys anywhere. Image builds use **Kaniko** so there's no docker daemon and no root socket mount. The 8-stage pipeline does secret scanning, dependency vulnerability scanning, build & unit tests, image build & push, image vulnerability scanning, and ECR verification — all gated on HIGH/CRITICAL findings. A shared Jenkins library provides the pod template so all four services share one source of truth."*

---

## 2. The "Why" — Problem Statement

The legacy Jenkinsfile (still preserved as `Jenkinsfile.legacy`) had these problems:

| Problem | Risk |
|---|---|
| `agent any` ran builds directly on the Jenkins controller VM | One bad PR could corrupt `~/.m2`, ECR creds, or Docker cache for *every* subsequent build |
| `docker.sock` was mounted into builds | Any build had effective root on the Jenkins host (well-known privesc) |
| AWS credentials were a Jenkins credential, copied to env | Long-lived IAM access keys, no rotation, blast radius = entire build server |
| `gitleaks` + `trivy` + `docker` had to be pre-installed on the host | Tool drift between local dev and CI, package manager dependencies, host bloat |
| Only one Jenkins executor could run builds at a time efficiently | No horizontal scale; queue piles up on busy mornings |
| `mvn`, `docker build`, `docker push` ran serially in one shell | A failed `docker push` corrupted the host's image cache |

The migration brief was simple but strict:

1. **Jenkins controller stays on EC2** (it has months of build history, plugin state, credentials — not worth moving today).
2. **Builds must run on EKS** as ephemeral pods.
3. **No docker daemon, no docker.sock, no `--privileged`.**
4. **No static AWS keys** — must use IRSA.
5. **Least-privilege RBAC** in the agent namespace.
6. **One pod template, four services** — DRY through a shared Jenkins library.
7. Future-ready for **separate Build and App AWS accounts** (Phase 2).

---

## 3. High-Level Architecture

```
                ┌─────────────────────────────────────────────────────────┐
                │ Developer push                                          │
                │ ──── GitHub: mulesanket/devops-deployment ─── branch    │
                │                       │                                 │
                │                       ▼  webhook                        │
                │              ┌────────────────────┐                     │
                │              │ Jenkins controller │  Ubuntu EC2         │
                │              │  (EC2, no builds)  │  Build account      │
                │              │  jenkins-github-pat│  483829975256       │
                │              │  K8s plugin        │                     │
                │              └─────────┬──────────┘                     │
                │                        │ (1) HTTPS + bearer token        │
                │                        ▼                                 │
                │     ┌─────────────────────────────────────────┐         │
                │     │ EKS cluster: shopease-webapp-dev        │         │
                │     │ Namespace: jenkins-cicd-agents          │         │
                │     │                                         │         │
                │     │  ┌───────────────────────────────────┐  │         │
                │     │  │ Build Pod  (ephemeral)            │  │         │
                │     │  │  ┌─────────┐  ┌────────────────┐  │  │         │
                │     │  │  │  jnlp   │  │ workspace-vol  │  │  │         │
                │     │  │  └────┬────┘  │ /home/jenkins  │  │  │         │
                │     │  │  ┌────▼────┐  │ /agent (shared │  │  │         │
                │     │  │  │ maven   │◄─┤  by all)       │  │  │         │
                │     │  │  ├─────────┤  │                │  │  │         │
                │     │  │  │ kaniko  │◄─┤                │  │  │         │
                │     │  │  ├─────────┤  │                │  │  │         │
                │     │  │  │ aws     │◄─┤                │  │  │         │
                │     │  │  ├─────────┤  │                │  │  │         │
                │     │  │  │ tools   │◄─┘ (trivy + jq)   │  │  │         │
                │     │  │  └─────────┘                   │  │         │
                │     │  │  SA: jenkins-agent-builder     │  │  │         │
                │     │  │      (IRSA annotated)          │  │         │
                │     │  └───────────────────────────────────┘  │         │
                │     └────────────────────┬────────────────────┘         │
                └──────────────────────────┼─────────────────────────────┘
                                           │ (2) IRSA exchange
                                           ▼
                                  STS AssumeRoleWithWebIdentity
                                           │
                                           ▼
                          IAM role: shopease-webapp-development-ci-agent-irsa
                          (scoped: ECR push to *-development-* + S3 bucket)
                                           │
                       ┌───────────────────┼───────────────────┐
                       ▼                                       ▼
              ECR (push image)                    S3 ci-artifacts (future use:
              483829975256.dkr.ecr...              SBOMs, scan reports, jars)
              /shopease-webapp-
              development-*
                       │
                       │ (later) ArgoCD / kubectl apply
                       ▼
              EKS app namespaces (shopease-webapp-development)
              auth / product / cart / order Deployments
```

**Key flow numbered:**
1. **Controller → EKS API**: Static ServiceAccount bearer token (the Fabric8 K8s client doesn't support `aws eks get-token` exec credentials).
2. **Pod → AWS APIs**: IRSA via the EKS pod-identity-webhook (no keys, only OIDC).

---

## 4. What I Personally Built (Talking Points)

### 4.1 Build cluster prep (Kubernetes side)

| File | Purpose |
|---|---|
| `deployment-kubernetes/base/ci-cd-jenkins-namespace.yaml` | Dedicated namespace `jenkins-cicd-agents` — isolated from app namespaces |
| `deployment-kubernetes/base/ci-cd-jenkins-rbac.yaml` | `Role jenkins-agent-manager` + `RoleBinding` to the group `jenkins-agent-admins` (mapped via EKS Access Entry for human admins) |
| `deployment-kubernetes/base/ci-cd-jenkins-sa.yaml` | SA `jenkins-controller` + static token Secret + RoleBinding — what the Jenkins controller uses to talk to the EKS API |
| `deployment-kubernetes/base/ci-cd-jenkins-agent-sa.yaml` | SA `jenkins-agent-builder` annotated with `eks.amazonaws.com/role-arn: …ci-agent-irsa`; every build pod runs as this SA |
| `deployment-kubernetes/base/ci-cd-jenkins-maven-cache-pvc.yaml` | PVC reserved for `~/.m2`; currently `emptyDir` because EBS CSI driver not installed yet |

### 4.2 IRSA + S3 (Terraform side)

File: `infrastructure-terraform/environments/development/ci-cd.tf`

- **S3 bucket** `shopease-webapp-development-ci-artifacts`
  - Versioning enabled
  - SSE-S3 server-side encryption
  - Public access block (all four flags true)
  - Lifecycle: 30d → STANDARD_IA, 90d expire, 30d non-current expire
- **IAM policy** narrowly scoped:
  - `ecr:GetAuthorizationToken` on `*` (the API requires `*`)
  - All push/read ECR actions on `arn:aws:ecr:ap-south-1:483829975256:repository/shopease-webapp-development-*`
  - S3 read/write on the artifacts bucket only
- **IRSA module** call — produces role `shopease-webapp-development-ci-agent-irsa` with trust condition `oidc:sub == system:serviceaccount:jenkins-cicd-agents:jenkins-agent-builder` and `aud == sts.amazonaws.com`

### 4.3 Shared Jenkins library

Repo path: `jenkins-library/vars/shopeaseAgent.groovy` (also `logger.groovy` for stage banners and ANSI colors).

The library is registered in Jenkins → Manage → Global Pipeline Libraries with:
- Name: `shopease-jenkins-library`
- Repo: `https://github.com/mulesanket/devops-deployment.git`
- Default version: `development`
- Library path: `jenkins-library/`

A service Jenkinsfile pulls it with `@Library('shopease-jenkins-library') _` and gets `shopeaseAgent(serviceName: 'auth-service')` returning a complete pod YAML.

### 4.4 The 8-stage pipeline

Per service (currently auth-service shipped; cart/order/product to follow):

```
1. Setup & Environment        (container: aws)     → IRSA proof, GIT_SHA, derive IMAGE_URI
2. Secret Scan                (container: tools)   → trivy fs --scanners secret, gate on findings
3. Dependency Vuln Scan       (container: tools)   → trivy fs --scanners vuln (HIGH/CRIT gate)
4. Build & Unit Tests         (container: maven)   → mvn -pl auth-service -am -B verify
5. Image Build & Push         (container: kaniko)  → Kaniko → ECR (IRSA), two tags
6. Image Vulnerability Scan   (container: tools)   → trivy image against ECR (fixed-only)
7. Verify ECR Push            (container: aws)     → aws ecr describe-images
8. Build Summary              (no container)       → printable summary
```

End-to-end time on a warm node: **~2 minutes**.

---

## 5. The Five Things I'll Lead With In An Interview

1. **"I moved builds off the Jenkins controller and onto ephemeral EKS pods — eliminated `docker.sock` mounting and host tool drift."**
2. **"I use Kaniko, not Docker, so there's no daemon and ECR auth flows entirely through IRSA — no `docker login` step needed."**
3. **"IRSA, not long-lived keys. The pod's ServiceAccount is annotated with a role; the EKS pod-identity-webhook injects OIDC token env vars; the AWS SDK exchanges them at STS."**
4. **"All four services share one pod template via a shared Jenkins library, so changing the agent definition is a one-file edit."**
5. **"The pipeline has three security gates — secret scan, dependency vuln scan, image vuln scan — all running Trivy. The image scan runs *after* push so it scans the actual ECR image, not the local layers."**

---

## 6. The Build Account / App Account Split (Phase 2 — Talking Point Only)

Today everything lives in account `483829975256`. The next iteration:

```
┌────────────────────────┐     AssumeRole       ┌──────────────────────────┐
│ Build Account          │ ───────────────────► │ App Account              │
│ - Jenkins controller   │     (cross-account)  │ - Production EKS         │
│ - Build EKS cluster    │                      │ - Production ECR pulls   │
│ - ci-agent-irsa role   │                      │ - Trusts ci-agent-irsa   │
└────────────────────────┘                      └──────────────────────────┘
```

Why it matters in an interview: shows you understand **blast radius** and **identity federation** — exactly the senior-track signal interviewers fish for.

---

## 7. The Cheat Card

| Question | One-line answer |
|---|---|
| Where do builds run? | Ephemeral pods in EKS `jenkins-cicd-agents` namespace |
| How does Jenkins talk to EKS? | K8s plugin → static SA bearer token over HTTPS |
| How does the pod talk to AWS? | IRSA → STS AssumeRoleWithWebIdentity |
| Image build engine? | Kaniko (daemonless, no `--privileged`) |
| Scanner? | Trivy — secret + fs vuln + image vuln |
| Tag scheme? | `:<short-sha>` (immutable) + `:<branch>-latest` (moving) |
| Shared between services? | `shopeaseAgent` Groovy function in `jenkins-library/vars/` |
| Secrets in pipeline? | Only `jenkins-github-pat` (for SCM checkout); AWS via IRSA |
| Build pod resource ask? | CPU 350m, mem 1.5Gi (sum of requests); scheduler-friendly |
| End-to-end time? | ~2 min on warm cluster |
