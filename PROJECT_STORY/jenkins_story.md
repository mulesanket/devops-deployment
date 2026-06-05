# Jenkins Interview Story — AWS DevOps Role
## School Spider / IRIS UK — Enterprise Production Standard

> **Audience:** AWS DevOps interview, 3 YOE.
> **Use this as:** Speaking notes. Read it aloud. Every section is a standalone answer.
> **Tone:** You built this, you own it, you can defend every decision.

---

# 0. The 90-Second Opening Pitch (memorize this)

> *"At School Spider — an EdTech SaaS for UK schools, part of IRIS — we used Jenkins as our CI/CD backbone running on EKS. The legacy setup had Jenkins doing everything: running builds directly on the controller, mounting the Docker socket, storing long-lived AWS keys in Jenkins credentials, all tools pre-installed on the host. It was fragile, insecure, and didn't scale.*
>
> *I led the migration to a model where the controller only orchestrates and every build runs on an ephemeral pod inside our EKS cluster. Each pod has five containers — jnlp for the agent, maven for builds, kaniko for image builds, aws-cli for AWS API calls, and trivy for scanning — all sharing one workspace volume. AWS access uses IRSA — no static keys anywhere. Image builds use Kaniko — no Docker daemon, no socket mount, no privileged containers. All four services share one pod template via a Jenkins shared library, so a change to the agent definition is a one-file edit. The pipeline itself has 9 stages: environment setup, secret scanning, dependency scanning, build and unit tests, image build and push, image vulnerability scanning, artifact publishing to S3, ECR verification, and CD trigger. A separate CD pipeline handles deployment to EKS with IRSA + EKS Access Entry auth, and `kubectl apply` with rollout status monitoring.*
>
> *I personally owned the cluster-side RBAC, the IRSA Terraform modules, the shared library, the CI and CD pipeline templates, the caching strategy, the branching model, and the production approval gate."*

---

# 1. Why We Had to Change (the problem statement)

Understanding the problems you solved is what separates a 3-YOE candidate from a junior who "just ran pipelines."

## The Legacy Jenkins (what you walked into)

| Problem | Risk / Impact |
|---|---|
| `agent any` → builds ran on the Jenkins controller VM | One bad PR could poison `~/.m2`, docker cache, or installed tools for every subsequent build |
| `docker.sock` mounted into builds | Any build had effective root on the host — a known container escape vector |
| Long-lived AWS IAM access keys stored in Jenkins credentials | No rotation, leaked in build logs once, blast radius = entire AWS account |
| All tools (`docker`, `trivy`, `gitleaks`, `mvn`) installed on the host VM | Tool drift between CI and dev machines; `apt upgrade` during a deploy window broke three builds |
| Single Jenkins executor, builds queued serially | School start-of-term pushes caused 45-minute queue backs |
| No image scanning in pipeline | Deployed a Spring Boot image with a critical Log4j CVE to staging — caught by a security audit, not us |
| No secret scanning | A developer accidentally committed an AWS access key in a `.properties` file; wasn't caught for 3 days |
| Deployment was a manual `kubectl apply` from a developer's laptop | No auditability, no rollback, no approval gate |

## The Migration Goals

1. **Controller only orchestrates** — no builds, no tools on the controller VM.
2. **Every build runs as an ephemeral pod in EKS** — isolated, reproducible, scalable.
3. **No docker daemon, no docker.sock, no `--privileged`.**
4. **No static AWS keys** — IRSA only.
5. **Least-privilege RBAC** — CI agents can push images; they cannot touch app namespaces.
6. **All 4 services share one template** — DRY via a shared Jenkins library.
7. **Three automated security gates** — secret scan, dependency scan, image scan.
8. **Separate CD pipeline** — build contract ends at "image in ECR"; deployment is a separate job.

---

# 2. Architecture — How It All Fits Together

```
Developer push → GitHub → Webhook
                               ↓
                   ┌─────────────────────────┐
                   │  Jenkins Controller     │
                   │  (EC2, eu-west-2)       │
                   │  Orchestrates only      │
                   │  K8s Plugin connects    │
                   │  via SA bearer token    │
                   └──────────┬──────────────┘
                              │ HTTPS (K8s API)
                              ↓
       ┌──────────────────────────────────────────────────────────┐
       │  EKS Cluster: schoolspider-prod                         │
       │  Namespace: jenkins-cicd-agents                         │
       │                                                          │
       │  ┌───────────────────────────────────────────────────┐  │
       │  │  Ephemeral Build Pod (lifetime = one build)       │  │
       │  │                                                   │  │
       │  │  ┌──────┐ ┌───────┐ ┌────────┐ ┌─────┐ ┌─────┐  │  │
       │  │  │ jnlp │ │ maven │ │ kaniko │ │ aws │ │tools│  │  │
       │  │  └──────┘ └───────┘ └────────┘ └─────┘ └─────┘  │  │
       │  │       └────────────────────┘                      │  │
       │  │           shared workspace volume                  │  │
       │  │           /home/jenkins/agent                      │  │
       │  │                                                   │  │
       │  │  ServiceAccount: jenkins-agent-builder            │  │
       │  │  (IRSA annotated → ci-agent-irsa role)            │  │
       │  └───────────────────────────────────────────────────┘  │
       └──────────────────────────────────────────────────────────┘
                              │ IRSA / STS
                              ↓
              IAM Role: schoolspider-prod-ci-agent-irsa
              - ecr:PutImage on schoolspider-prod-* repos
              - s3:PutObject on ci-artifacts bucket
              - eks:DescribeCluster (for CD kubeconfig)
                    ↓               ↓
                  ECR             S3 ci-artifacts
              (image push)        (jar, reports, manifest, pointer)
                    ↓
              CD Job (Jenkinsfile.cd) → kubectl apply → EKS app namespace
```

**Two auth layers to highlight:**
1. **Controller → EKS API**: Static ServiceAccount bearer token (the Fabric8 K8s client used by the Jenkins plugin doesn't support `aws eks get-token` exec credentials — known limitation).
2. **Build pod → AWS APIs**: IRSA → STS `AssumeRoleWithWebIdentity` → temp creds. No static keys anywhere.

---

# 3. The Shared Jenkins Library (how teams share one template)

## What it is

A Git repository path (`jenkins-library/`) that Jenkins loads as a **Global Pipeline Library**. All pipelines call it with:

```groovy
@Library('schoolspider-jenkins-library') _
```

This exposes global steps like `schoolspiderAgent(serviceName: 'parent-comms')` which returns a complete pod YAML string for the `agent { kubernetes { yaml ... } }` block.

## Why this matters (the DRY argument)

Without the library, each of the 12 services has a copy of the pod template YAML inside its Jenkinsfile. When you need to:
- Bump the Trivy version from `0.55` to `0.56`
- Rotate a container image
- Change memory limits on the maven container

...you have 12 files to update, 12 PRs to review, 12 chances to drift. **With the library, it's one file, one PR, instant propagation.**

## Library structure

```
jenkins-library/
├── vars/
│   ├── schoolspiderAgent.groovy      ← CI pod template (5 containers)
│   ├── schoolspiderDeployer.groovy   ← CD pod template (2 containers: aws + kubectl)
│   ├── logger.groovy                 ← ANSI color helpers for stage headers
│   └── schoolspiderPipeline.groovy   ← full pipeline orchestrator (Phase 2 goal)
└── resources/
    └── podtemplates/                 ← raw YAML fallbacks for debugging
```

## Safe rollout of library changes

```
1. Make change on feature branch: jenkins-library@feature/bump-trivy-0.56
2. Point one smoke-test job at the branch:
   @Library('schoolspider-jenkins-library@feature/bump-trivy-0.56') _
3. Green? Merge to development → auto-propagates to all services.
4. For breaking changes: release tag (v1.3.0), pin critical services with
   @Library('schoolspider-jenkins-library@v1.3.0') _
   while canary service runs @development.
```

**Senior detail:** Without a release strategy for the library, a bad merge to `development` breaks all 12 services simultaneously. The tag-pinning pattern means you can graduate services one at a time.

---

# 4. The CI Pipeline — Stage by Stage

## 4.0 Pipeline skeleton

```groovy
@Library('schoolspider-jenkins-library') _

pipeline {
    agent {
        kubernetes {
            yaml schoolspiderAgent(serviceName: 'parent-comms')
            defaultContainer 'jnlp'
            showRawYaml false
        }
    }
    options {
        timeout(time: 30, unit: 'MINUTES')
        timestamps()
        disableConcurrentBuilds()
        ansiColor('xterm')
        buildDiscarder(logRotator(numToKeepStr: '5', daysToKeepStr: '30'))
    }
    environment {
        SERVICE_NAME    = 'parent-comms'
        AWS_REGION      = 'eu-west-2'
        ECR_REPO_NAME   = "schoolspider-prod-${SERVICE_NAME}"
        SAFE_BRANCH     = "${env.BRANCH_NAME ?: env.GIT_BRANCH?.replaceAll('^origin/', '') ?: 'development'}"
        ARTIFACT_BUCKET = 'schoolspider-prod-ci-artifacts'
    }
    stages {
        stage('Setup & Environment')       { container('aws')    { ... } }
        stage('Restore Caches')            { container('aws')    { ... } }
        stage('Secret Scan')               { container('tools')  { ... } }
        stage('Dependency Vuln Scan')      { container('tools')  { ... } }
        stage('Build & Unit Tests')        { container('maven')  { ... } }
        stage('Image Build & Push')        { container('kaniko') { ... } }
        stage('Image Vulnerability Scan')  { container('tools')  { ... } }
        stage('Save Caches & Publish S3')  { container('aws')    { ... } }
        stage('Verify ECR Push')           { container('aws')    { ... } }
        stage('Build Summary')             { ... }
        stage('Trigger CD')                { when { branch 'development' } ... }
    }
}
```

## 4.1 Stage: Setup & Environment

```groovy
container('aws') {
    env.AWS_ACCOUNT_ID = sh(script: 'aws sts get-caller-identity --query Account --output text',
                            returnStdout: true).trim()
    env.ECR_REPO  = "${env.AWS_ACCOUNT_ID}.dkr.ecr.${env.AWS_REGION}.amazonaws.com/${env.ECR_REPO_NAME}"
    env.GIT_SHA   = (env.GIT_COMMIT ?: 'unknown').take(7)
    env.IMAGE_TAG = "${env.GIT_SHA}"
    env.IMAGE_URI = "${env.ECR_REPO}:${env.IMAGE_TAG}"
}
```

**Three things to call out:**
- `aws sts get-caller-identity` twice — once for the account ID value, once to **print the assumed-role ARN** as proof that IRSA wired up. Interviewers love this — you know how to validate your auth assumptions.
- `env.GIT_COMMIT.take(7)` — Groovy shorthand, no `git` binary needed, works in any container.
- `SAFE_BRANCH` ladder (`BRANCH_NAME ?: GIT_BRANCH ?: 'development'`) — defensive because multibranch pipelines set `BRANCH_NAME`, classic pipeline jobs set `GIT_BRANCH` (prefixed with `origin/`), and in both cases you need a clean string for image tags.

## 4.2 Stage: Restore Caches (S3 tarball pattern)

```groovy
container('aws') {
    sh '''
        aws s3 cp "${CACHE_S3_M2}"    "${WORKSPACE}/.cache/m2.tar.gz"    || true
        aws s3 cp "${CACHE_S3_TRIVY}" "${WORKSPACE}/.cache/trivy-db.tar.gz" || true
    '''
}
// Extract in containers that have tar but no aws-cli
container('maven') { sh 'tar -xzf "${WORKSPACE}/.cache/m2.tar.gz" -C /root/.m2 || true' }
container('tools')  { sh 'tar -xzf "${WORKSPACE}/.cache/trivy-db.tar.gz" -C /root/.cache/trivy || true' }
```

**Why S3 tarballs, not EBS PVCs? (you will be asked this)**

| Approach | Problem |
|---|---|
| EBS PVC | AZ-pinned. `ReadWriteOnce` blocks parallel builds. Cross-service dependency pollution. 10–30s attach latency. Orphan volume cost. |
| EFS PVC | Maven `.m2` has tens of thousands of tiny files → EFS metadata latency makes EFS-cached builds **slower** than no cache at all. |
| hostPath | Blocked by Pod Security Standards `restricted`. Cache lost on node replacement (Karpenter, spot reclaim). |
| **S3 tarball** ✅ | Multi-AZ free, per-service keys (no cross-contamination), restores and saves in parallel, cache miss is non-fatal (`|| true`), 30–60s savings on hit. Same pattern as GitHub Actions `actions/cache` and GitLab CI `cache:`. |

**Per-service Maven, shared Trivy DB:**
- Maven: `_caches/parent-comms/m2.tar.gz` — per-service to avoid version conflicts.
- Trivy DB: `_caches/_shared/trivy-db.tar.gz` — CVE data is service-agnostic; one DB for all.

## 4.3 Stage: Secret Scan

```sh
trivy fs \
  --scanners secret \
  --exit-code 1 \
  --no-progress \
  --format json --output "${WORKSPACE}/trivy-secrets-report.json" \
  "application-backend/${SERVICE_NAME}"

trivy convert --format table "${WORKSPACE}/trivy-secrets-report.json"
```

**Gates:** `--exit-code 1` — any secret found fails the stage immediately. Zero tolerance.

**Why Trivy not gitleaks?** One tool, three scan modes (secret + fs vuln + image). Fewer images to maintain in the pod template, consistent JSON schema, one documentation source.

## 4.4 Stage: Dependency Vulnerability Scan

The **two-pass pattern** (important — be ready to explain why):

```sh
# Pass 1: write JSON, don't fail
trivy fs --scanners vuln --severity HIGH,CRITICAL --exit-code 0 --format json --output report.json .

# Pretty-print for humans in the log
trivy convert --format table --severity HIGH,CRITICAL report.json

# Pass 2: enforce gate
trivy fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --quiet .
```

**Why two passes?**
- We want the JSON archived in S3 for forensics — every finding captured, even if the gate passes later.
- We want the stage to fail on HIGH/CRITICAL.
- `jq` is not in the minimal Trivy image, so we can't parse the first run's output.
- Solution: run twice. Pass 2 is fast because the DB is already cached in `/root/.cache/trivy/`.

**What to do when a scan fails?** This comes up. The answer:
1. `trivy fs . --scanners vuln --severity HIGH,CRITICAL --format json | jq '.Results[].Vulnerabilities[] | {id:.VulnerabilityID, fix:.FixedVersion}'`
2. Fix: upgrade the dependency if a fix exists. If no fix exists: add `.trivyignore` with a time-limited exception + Jira ticket + comment explaining the risk acceptance.
3. If the CVE is in a transitive dependency you don't control: open an issue upstream, pin to a clean version of the parent, document the exception.
4. **Never set `--exit-code 0` to skip the gate** without going through the formal exception process.

## 4.5 Stage: Build & Unit Tests

```sh
cd application-backend
mvn -pl ${SERVICE_NAME} -am -B -ntp verify
```

| Flag | Meaning |
|---|---|
| `-pl parent-comms` | Build only this module |
| `-am` | Also-Make — build upstream dependencies (e.g. `schoolspider-common`) |
| `-B` | Batch mode — no interactive prompts, machine-readable output |
| `-ntp` | No transfer progress — suppresses download bars |
| `verify` | Full lifecycle: compile + test + package + verify (integration checks) |

Post step: `junit testResults: '...surefire-reports/*.xml', allowEmptyResults: false`
— `allowEmptyResults: false` makes zero tests an unstable build, not a pass.

## 4.6 Stage: Image Build & Push (Kaniko)

```sh
/kaniko/executor \
  --context "${WORKSPACE}/application-backend" \
  --dockerfile "${SERVICE_NAME}/Dockerfile" \
  --destination "${IMAGE_URI}" \
  --destination "${ECR_REPO}:${SAFE_BRANCH}-latest" \
  --label "git.sha=${GIT_SHA}" \
  --snapshot-mode=redo \
  --use-new-run
```

**Why Kaniko not Docker?**
1. **Security** — no docker daemon, no `docker.sock` mount, no `--privileged` container. Docker socket mount = effective root on the node.
2. **ECR auth built-in** — Kaniko's debug image ships with the AWS SDK. IRSA env vars (`AWS_ROLE_ARN`, `AWS_WEB_IDENTITY_TOKEN_FILE`) are auto-detected. No `docker login` step.
3. **One step** — build + push in one invocation (legacy was three: build, tag, push).
4. **Immutable ECR repos** — ECR repo set to `IMMUTABLE` so a SHA tag can never be overwritten.

**Two `--destination` tags:**
- `:<git-sha>` — immutable, never overwritten. Production deploys always use this.
- `:<branch>-latest` — moving, convenience for dev environments.

**Senior probe: "What about BuildKit?"** — Valid alternative, faster for complex multi-stage builds via its DAG cache. For Spring Boot fat JAR images (one `COPY`, no multi-stage), the speedup is marginal. Would evaluate if build times grew past 10 minutes.

## 4.7 Stage: Image Vulnerability Scan

```sh
# Trivy pulls the image from ECR (IRSA gives it pull permission)
trivy image \
  --exit-code 1 \
  --severity HIGH,CRITICAL \
  --ignore-unfixed \
  "${IMAGE_URI}"
```

**Why scan after push, not before?**
Scanning the local layers before push misses vulnerabilities that Trivy can only detect when examining the final image manifest in ECR (layer ordering, metadata). Scanning ECR directly also gives us a record in ECR's scan history.

**`--ignore-unfixed`**: don't fail on CVEs that have no available fix. Failing on unfixed CVEs is unactionable noise. We alert on them in the report but don't block.

## 4.8 Stage: Save Caches & Publish to S3

```sh
# Save caches on success only (don't poison with broken-build state)
tar -czf "${WORKSPACE}/.cache/m2.tar.gz" -C /root/.m2 .
aws s3 cp "${WORKSPACE}/.cache/m2.tar.gz" "${CACHE_S3_M2}" --metadata "service=${SERVICE_NAME},build=${BUILD_NUMBER}"

# Build manifest (the canonical record of what was built)
cat > manifest.json <<EOF
{
  "service": "${SERVICE_NAME}",
  "gitSha": "${GIT_SHA}",
  "imageUri": "${IMAGE_URI}",
  "imageDigest": "${IMAGE_DIGEST}",
  "buildNumber": "${BUILD_NUMBER}",
  "timestamp": "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}
EOF
aws s3 cp manifest.json "s3://${ARTIFACT_BUCKET}/${SERVICE_NAME}/manifests/..."

# S3 pointer: latest successful build per branch
aws s3 cp latest-pointer.json "s3://${ARTIFACT_BUCKET}/${SERVICE_NAME}/latest/${SAFE_BRANCH}.json"
```

**The S3 pointer pattern is the key to the CD pipeline.** The CD job reads `s3://.../parent-comms/latest/development.json` to find the SHA to deploy when no explicit SHA is given. This decouples CI from CD — CI owns writing the pointer, CD owns reading it.

## 4.9 Stage: Trigger CD

```groovy
stage('Trigger CD') {
    when { anyOf { branch 'development'; branch 'main' } }
    steps {
        build job: 'parent-comms-cd',
              parameters: [string(name: 'GIT_SHA', value: env.GIT_SHA)],
              wait: false,       // CI returns SUCCESS immediately
              propagate: false   // CD failure doesn't fail CI
    }
}
```

**`wait: false, propagate: false` — the design principle:**
- CI's contract is "image is built, scanned, and pushed to ECR." That's done.
- A CD failure (Kubernetes timing issue, flaky readiness probe) shouldn't mark the CI build failed. They're different failure domains.
- A CD failure should be visible on the CD job, not buried in the CI log.

---

# 5. The CD Pipeline (Jenkinsfile.cd) — Deploy to EKS

## 5.1 Why a separate CD pipeline

| If CI and CD are one pipeline | Problem |
|---|---|
| Rolling back a deployment means re-running Trivy, Maven, Kaniko | Slow, expensive, silly |
| Emergency re-deploy (config change only) triggers a full build | Blocks the pipeline for 2 min |
| CI pod template (5 containers, ~3.5 CPU) is wasteful for `kubectl apply` | Pod scheduling overhead |
| Build failure deep in scan stages prevents a rollback | Can't deploy a known-good SHA |

Solution: CI ends at "image in ECR." CD is a separate 2-container pod (aws + kubectl), ~30 seconds.

## 5.2 CD Pipeline stages

```
1. Setup & Resolve Tag
   - Param > upstream param > S3 latest pointer > fail
   - aws sts get-caller-identity (IRSA proof)

2. Verify Image in ECR
   - aws ecr describe-images --image-ids imageTag=${GIT_SHA}
   - Refuse to deploy phantom tags (typo, expired lifecycle policy)

3. Configure kubectl
   - aws eks update-kubeconfig --name ${EKS_CLUSTER}
   - kubectl auth can-i patch deployments (pre-flight RBAC check)

4. Render Manifest
   - sed -i "s|IMAGE_TAG_PLACEHOLDER|${GIT_SHA}|g" deployment.yaml
   - IMAGE_TAG_PLACEHOLDER lives as a literal in Git — always valid YAML, diffable

5. Dry Run / Diff (if DRY_RUN=true)
   - kubectl diff -f deployment.yaml

6. Apply
   - kubectl apply -f deployment.yaml

7. Wait for Rollout
   - kubectl rollout status deployment/${DEPLOYMENT_NAME} -n ${NAMESPACE} --timeout=5m

8. Diagnostics on Failure
   - kubectl get events --sort-by=.lastTimestamp
   - kubectl logs --previous (if pods crashed)

9. Tag S3 as Deployed
   - Write deployed/<branch>.json pointer
```

## 5.3 Tag Resolution (fail-closed)

```groovy
String resolvedSha = (params.GIT_SHA ?: '').trim()

if (!resolvedSha) {
    // Read S3 latest pointer
    String pointer = sh(script: "aws s3 cp s3://${ARTIFACT_BUCKET}/${SERVICE_NAME}/latest/${TARGET_BRANCH}.json -",
                        returnStdout: true).trim()
    resolvedSha = sh(script: "echo '${pointer}' | grep -oE '\"gitSha\":\\s*\"[^\"]+\"' | ...",
                     returnStdout: true).trim()
}

if (!resolvedSha) {
    error("Could not resolve a git SHA to deploy. Run CI first.")  // fail-closed
}
```

**Priority:** param SHA → upstream CI param → S3 pointer → **error**. Never deploy `null` or `unknown`.

## 5.4 Auth chain for CD (two layers, both must pass)

```
Pod SA: jenkins-agent-builder
  ↓ IRSA
IAM Role: schoolspider-prod-ci-agent-irsa
  → eks:DescribeCluster (to fetch kubeconfig)
  ↓ aws eks update-kubeconfig
kubectl → EKS API
  ↓ EKS Access Entry: ci-agent-irsa → K8s group "schoolspider-deployers"
  ↓ RoleBinding: schoolspider-deployers → Role: schoolspider-deployer
  → get/list/watch/update/patch deployments (app namespace only)
  → get/list/watch replicasets, pods, events, configmaps, services
  → NO secrets, NO serviceaccounts, NO rolebindings, NO ingresses
```

**Why the Role is deliberately limited (senior-level detail):**
The CD pipeline needs to patch a Deployment. It does **not** need to read secrets, modify RBAC, or touch ingresses. If the CD pod is compromised, the attacker can at most set a Deployment image — they cannot escalate to read Secrets Manager values, modify RBAC to give themselves more access, or affect other namespaces.

## 5.5 Rollback Recipe

```
1. Open parent-comms-cd Jenkins job
2. Build with Parameters
3. GIT_SHA = the SHA of the last-known-good build (from prior build summary log)
4. Run
```

There is no "rollback command" — rolling back is just deploying a previous SHA. The same code path runs. The same health checks run. If the "rollback" fails, you know immediately.

**S3 `deployed/` pointer is updated after every successful deploy**, so the next blank-SHA deploy won't accidentally undo a rollback.

---

# 6. Branching Strategy & Environment Promotion

## 6.1 GitFlow-lite

| Branch | Environment | CI? | CD? |
|---|---|---|---|
| `feature/*` | none | ✅ build + test | ❌ |
| `development` | dev | ✅ | ✅ auto on merge |
| `qa` | qa | ✅ | ✅ auto on merge |
| `release/x.y.z` | staging | ✅ | ✅ auto on branch create |
| `master` | prod | ✅ | ⚠️ manual + `input` approval gate |
| `hotfix/*` | prod (emergency) | ✅ | ⚠️ manual |

## 6.2 The golden rule: Build once, deploy everywhere

CI builds **once** per commit. The image `:<git-sha>` is what QA tested. The same image byte-for-byte goes to staging. The same image goes to production. There is no "build for production" — that's where "works in staging, fails in prod" bugs come from.

```
ECR: schoolspider-prod-parent-comms:a1b2c3d
     ↓         ↓         ↓         ↓
    dev       qa       stage      prod
  (auto)    (auto)    (auto)   (approval)
```

## 6.3 Production approval gate

```groovy
stage('Production Approval') {
    when { expression { params.ENVIRONMENT == 'production' } }
    steps {
        timeout(time: 30, unit: 'MINUTES') {
            input message: "Deploy ${env.GIT_SHA} to PRODUCTION?",
                  submitter: 'release-managers,oncall-lead'
        }
    }
}
```

**Layered controls:**
1. Default param = `development` — accidental clicks land in dev.
2. Jenkins RBAC — only `release-managers` group has `Build` permission on `*-cd` jobs.
3. `input` step — pauses the pipeline; only listed submitters can approve; 30-min timeout auto-aborts.
4. K8s RBAC — even if someone bypassed the gate, the IRSA role can only patch deployments in the target namespace.

---

# 7. What I Personally Did (Ownership Claims — 3-YOE Level)

Be specific. "I did X and the outcome was Y."

## 7.1 The IRSA Refactor

- "The legacy pipeline had a long-lived IAM access key in Jenkins credentials that was 2 years old and rotated once. I replaced it entirely with IRSA — set up the OIDC provider in the EKS cluster, wrote the Terraform IRSA module, annotated the agent ServiceAccount, and validated by running `aws sts get-caller-identity` inside a live build pod. Removed the last static key from Jenkins."
- **Impact:** Zero static AWS keys in CI. Key rotation is not a concern because keys don't exist.

## 7.2 Kaniko Migration

- "The legacy pipeline mounted `docker.sock` and ran `docker build` as root. I replaced it with Kaniko — no socket, no privileged container. As a side effect, Kaniko's AWS SDK picked up IRSA automatically, so I was able to remove the `docker login` step that was previously using a static `ecr:GetAuthorizationToken` call."
- **Impact:** Closed a known container-escape vector. Removed one credential type from the pipeline entirely.

## 7.3 S3 Cache Strategy

- "The team kept asking why builds were slow. I profiled builds with timestamps and found that 40% of build time was Maven downloading dependencies on every run. I designed the S3 tarball cache — per-service Maven cache, shared Trivy DB — same pattern as GitHub Actions cache. Added restore at build start, save at build end (success only to avoid cache poisoning). Cut average build time from 6 minutes to 3.5 minutes."
- **Impact:** 40% build time reduction, no infrastructure changes required.

## 7.4 Three-Gate Security Pipeline

- "After the Log4j near-miss, I added three Trivy gates: secret scan on the source code, dependency scan on `pom.xml`, image scan post-push. Set exit code 1 on HIGH/CRITICAL. Added a `.trivyignore` process with mandatory Jira ticket and time-bound exceptions. Caught 8 real HIGH CVEs in the next 3 months that would have shipped."
- **Impact:** First CVE caught by the pipeline 4 days after deployment.

## 7.5 The Shared Library

- "Each of the 12 services had its own copy of the pod template YAML inside its Jenkinsfile. I extracted the pod template into a shared library with a `schoolspiderAgent(serviceName: 'x')` call. When we bumped Trivy from 0.53 to 0.56, it was one commit in the library, not 12 PRs across 12 repos."
- **Impact:** Pod template governance is now a 1-file change with a clear owner.

## 7.6 The CD Pipeline & Rollback Story

- "We had a production incident where a bad release was deployed and rolled back via a developer running `kubectl set image` on their laptop — no record, no audit trail. I built the Jenkinsfile.cd — a dedicated CD pipeline with IRSA auth, EKS Access Entry, RBAC-controlled kubectl access, and rollback via SHA parameter. Next incident, we rolled back in 2 minutes, with a full audit trail in Jenkins, without any developer needing kubectl access to production."
- **Impact:** First rollback using the new pipeline: 2 min, full audit trail, no laptop credentials used.

---

# 8. Errors Faced & How They Were Resolved

This section is gold in interviews — shows you actually operate things, not just design them.

## Error 1: IRSA not working — pod authenticating as the node's EC2 role

**Symptom:** `aws sts get-caller-identity` inside the build pod showed the EC2 instance role (`i-xxxxx`) instead of `ci-agent-irsa`.

**Root cause:** The SA `jenkins-agent-builder` existed but was **missing the IRSA annotation** `eks.amazonaws.com/role-arn`. The pod-identity-webhook mutation only fires when the annotation is present.

**Diagnosis:**
```sh
kubectl get sa jenkins-agent-builder -n jenkins-cicd-agents -o yaml
# annotation was absent
```

**Fix:**
```yaml
metadata:
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::<acct>:role/schoolspider-prod-ci-agent-irsa
```

**Then:** pods already running don't pick up the annotation change. Deleted the running build pods so the next build spawned fresh pods with the annotation.

**Lesson:** IRSA injection is at pod admission time, not runtime. Annotate the SA, then delete existing pods.

---

## Error 2: ECR push returning 403 — "no basic auth credentials"

**Symptom:** Kaniko stage failed with `UNAUTHORIZED: authentication required`. IAM looked correct.

**Root cause:** The OIDC trust policy had a subtle typo — `system:serviceaccount:jenkins-agents:jenkins-agent-builder` (wrong namespace `jenkins-agents` instead of `jenkins-cicd-agents`).

**Diagnosis:**
```sh
kubectl exec -n jenkins-cicd-agents <pod> -- aws sts get-caller-identity
# → returned EC2 instance role, not ci-agent-irsa
```

The STS token was being issued to the wrong identity. The `sub` condition in the trust policy didn't match.

**Fix:** Updated the trust policy condition from `jenkins-agents` to `jenkins-cicd-agents`. Had to update the Terraform variable.

**Lesson:** Trust policy `sub` must be exact: `system:serviceaccount:<namespace>:<serviceaccount-name>`. Case-sensitive. No wildcards. Always validate with `aws sts get-caller-identity` inside the pod before trusting downstream API calls work.

---

## Error 3: Build pods stuck in `Pending` — "0/3 nodes available: insufficient memory"

**Symptom:** CI builds queued for 20+ minutes during a school-term start peak. Pods in `Pending`.

**Root cause:** The maven container was configured with `memory: requests: 2Gi`. With 3 pods scheduled simultaneously, the 3 `m6i.large` nodes (7.5 GB each) were fully allocated. Cluster Autoscaler wasn't scaling because it saw the nodes as "full" but the actual maven process was using only 800 MB.

**Diagnosis:**
```sh
kubectl describe pod -n jenkins-cicd-agents <pending-pod>
# Events: 0/3 nodes available: 3 Insufficient memory.
kubectl top pod -n jenkins-cicd-agents --containers
# maven container: 820Mi actual usage
```

**Fix (two-part):**
1. Short term: reduced maven `requests.memory` from 2Gi to 1.2Gi (still above actual usage for headroom).
2. Medium term: switched to **Karpenter** with a CI-specific NodePool that can spin up `m6i.large` spots within 40 seconds. The previous Cluster Autoscaler took 90–120 seconds per node.

**Lesson:** HPA and Karpenter both rely on `requests` being honest. Over-requesting wastes money and blocks scheduling. Use VPA in recommend mode to right-size, profile actual CI pod usage in `kubectl top`.

---

## Error 4: Maven build failing — `shopease-common module not found`

**Symptom:** `mvn -pl parent-comms verify` failed with `Could not resolve artifact schoolspider:schoolspider-common:jar:0.0.1-SNAPSHOT`.

**Root cause:** The `schoolspider-common` module was a sibling in the Maven multi-module project. Running `mvn -pl parent-comms verify` without `-am` (also-make) didn't build the dependency first. The S3 Maven cache didn't have it either because it was a SNAPSHOT.

**Fix:**
```sh
mvn -pl parent-comms -am -B verify
#              ^^^
# -am = "Also Make" upstream dependencies
```

**Lesson:** `-am` is mandatory for any multi-module Maven project where the service has intra-project dependencies. Add it and forget it — there's no downside.

---

## Error 5: Pipeline hanging at "Restore Caches" — no `tar` in `amazon/aws-cli` image

**Symptom:** The aws container successfully downloaded the tarball from S3, but the next extraction step in the same container hung indefinitely.

**Root cause:** The `amazon/aws-cli:2.17.18` image is minimal — no `tar` binary. The extraction step was trying to run `tar -xzf` inside the `aws` container, not the `maven` container.

**Fix:** Moved extraction to the container that has `tar`:
```groovy
container('aws')   { sh 'aws s3 cp ... "${WORKSPACE}/.cache/"' }   // download only
container('maven') { sh 'tar -xzf "${WORKSPACE}/.cache/m2.tar.gz" ...' }  // extract
```

**Lesson:** Know what's in each container image. The workspace volume is shared; you can download in one container and extract in another. This is a feature, not a bug — each container stays minimal and purpose-specific.

---

## Error 6: Trivy secret scan blocking on `.git/` — false positive on old commit

**Symptom:** Trivy secret scan found a "generic-api-key" pattern in `.git/COMMIT_EDITMSG` — it was a commit message that mentioned an API endpoint URL, not an actual key.

**Root cause:** Trivy scanned the entire workspace including `.git/`. A commit message happened to match the "generic API key" regex.

**Fix:**
```sh
trivy fs \
  --scanners secret \
  --skip-dirs ".git,target" \   # don't scan VCS metadata or build artifacts
  --exit-code 1 \
  "application-backend/${SERVICE_NAME}"
```

Also added `.trivyignore`:
```
# False positive: API endpoint URL in commit message, not a key
AVD-SECRET-0002
```

**Lesson:** Always scope Trivy scans to the source directory, not the workspace root. Exclude `.git/`, `target/`, `node_modules/` to avoid false positives on build artifacts and VCS metadata.

---

## Error 7: CD pipeline "kubectl rollout status" timing out — pods stuck in ContainerCreating

**Symptom:** CD pipeline stage `Wait for Rollout` timed out after 5 minutes. `kubectl get pods` showed new pods in `ContainerCreating`.

**Root cause:** The new image tag referenced in the Deployment had not been fully uploaded to ECR before the CD triggered. There was a race: CI triggered the CD job with `wait: false`, but Kaniko's ECR push was not fully committed when the CD ran. ECR returned the image layers but not the manifest.

**Diagnosis:**
```sh
kubectl describe pod <pod> -n schoolspider-prod
# Events: Failed to pull image: toomanyrequests / manifest not found
```

**Fix:**
1. Added `stage('Verify ECR Push')` in CI — `aws ecr describe-images --image-ids imageTag=${GIT_SHA}` — before triggering CD. This stage waits for ECR to confirm the image is queryable.
2. The CD job also has its own `Verify Image in ECR` stage that fails fast rather than deploying a phantom tag.

**Lesson:** ECR consistency after push is eventual. Always verify with `describe-images` before declaring "image is ready." Two verifications (CI side + CD side) provide belt-and-suspenders.

---

## Error 8: Jenkins controller can't talk to EKS — "the server doesn't have a resource type 'pods'"

**Symptom:** Jenkins failed to spawn build pods. Error in Jenkins logs: "the server doesn't have a resource type 'pods'".

**Root cause:** The EKS cluster had been upgraded from K8s 1.28 to 1.29. The `jenkins-controller-token` ServiceAccount token Secret had not been renewed. In K8s 1.24+, the API server may reject old token formats on version upgrades.

**Diagnosis:**
```sh
kubectl auth can-i create pods -n jenkins-cicd-agents \
  --token $(kubectl get secret jenkins-controller-token -o jsonpath='{.data.token}' | base64 -d)
```

**Fix:**
1. Deleted and re-created the `jenkins-controller-token` Secret (type `kubernetes.io/service-account-token`). K8s re-signed it against the new cluster CA.
2. Updated the Jenkins credential with the new token value.

**Lesson:** After EKS version upgrades, validate the controller-to-EKS SA token. The EKS cluster CA rotates on major version upgrades. Long-lived SA tokens need to be re-issued. Document this in the upgrade runbook.

---

# 9. Jenkins General Concepts You Must Know

## 9.1 Declarative vs Scripted Pipeline

| | Declarative | Scripted |
|---|---|---|
| Syntax | Structured DSL (`pipeline { stages { stage { steps {` ) | Groovy `node { stage { } }` |
| Validation | Validated by Jenkins before running | Runs as-is |
| `script {}` block | Escape hatch into Scripted within Declarative | N/A |
| When to use | Most pipelines — clean, auditable | Complex logic, dynamic stages |
| Our usage | 100% Declarative + `script {}` blocks for env var assignments |

## 9.2 Key options (and why each one)

```groovy
options {
    timeout(time: 30, unit: 'MINUTES')       // hung Maven won't run forever
    timestamps()                             // every line timestamped — critical for timing
    disableConcurrentBuilds()                // two pushes to same branch don't race on same pod
    ansiColor('xterm')                       // renders ANSI escape codes from logger.groovy
    buildDiscarder(logRotator(numToKeepStr: '5', daysToKeepStr: '30'))  // disk management
}
```

## 9.3 `container('x')` — how it works under the hood

When you write `container('maven') { sh 'mvn ...' }`, the Jenkins Kubernetes plugin:
1. Sends a `POST /api/v1/namespaces/jenkins-cicd-agents/pods/<pod>/exec?container=maven` WebSocket request to the K8s API.
2. The shell command runs inside the `maven` container's pid namespace.
3. All containers share the same workspace volume at `/home/jenkins/agent`, so files written by one container are immediately readable by another.

This is why you can download a tarball in `container('aws')` and extract it in `container('maven')`.

## 9.4 `env.VARIABLE` vs `environment {}` block

| | `environment {}` block | `script { env.X = ... }` |
|---|---|---|
| When evaluated | At pipeline startup, on the **controller** | At stage runtime, on the **agent** |
| Can use shell commands | ❌ (no container yet) | ✅ via `sh(returnStdout: true)` |
| Use for | Static values, env var construction | Dynamic values (account ID, git SHA) |

## 9.5 `when` conditions (used in multiple stages)

```groovy
when { branch 'development' }                          // multibranch only
when { expression { params.ENVIRONMENT == 'prod' } }   // param check
when { changeset '**/pom.xml' }                        // only if pom changed
when { anyOf { branch 'main'; branch 'development' } }
when { not { branch 'development' } }
```

## 9.6 `post` block — cleanup and notifications

```groovy
post {
    always   { junit testResults: '...xml'; archiveArtifacts '...' }
    success  { script { logger.logSuccess('Build passed') } }
    failure  { script { slackSend channel: '#alerts', message: "FAILED: ${BUILD_URL}" } }
    unstable { script { emailext ... } }
}
```

`always` runs even when the build fails — important for publishing test results and archiving reports so the failure is diagnosable.

## 9.7 Multibranch Pipeline vs Pipeline Job

| | Multibranch | Pipeline |
|---|---|---|
| Auto-discovers branches | ✅ | ❌ manual |
| Sets `BRANCH_NAME` | ✅ | ❌ (sets `GIT_BRANCH` with `origin/` prefix) |
| Per-branch build history | ✅ | ❌ |
| CD job should be | ❌ (single env per job) | ✅ |
| CI job should be | ✅ (build all feature branches) | ❌ |

## 9.8 Jenkins Kubernetes Plugin — how the controller talks to EKS

1. You configure a Cloud in Jenkins → Manage Jenkins → Clouds.
2. You provide: API server URL, CA certificate, ServiceAccount token, namespace.
3. The plugin uses the **Fabric8 K8s client** to `POST pods` when a build starts, and `DELETE pods` when it ends.
4. The `jnlp` container in the pod is pre-configured to connect back to the controller over a websocket, registering as a Jenkins agent.
5. The pod spec is rendered from the `yaml shopeaseAgent(...)` call in the `agent` block.

**Why static SA token and not `aws eks get-token`?** The Fabric8 client doesn't support exec-credential plugins. `aws eks get-token` requires running a process and reading its output — that's not how the K8s client SDK works. Using a static SA token is the pragmatic solution with a namespace-scoped RBAC blast radius.

---

# 10. Expected Interview Questions & Answers

## Q1. Walk me through your Jenkins pipeline.

**Answer:** Use the 90-second pitch in §0, then trace one request through the 9 stages. Keep it conversational — "first we set up environment variables and prove IRSA is wired correctly by running `sts:GetCallerIdentity`. Then we scan for secrets — any leak and the build fails immediately. Then dependency CVEs — HIGH/CRITICAL gate. Then Maven build and unit tests. Then Kaniko builds and pushes to ECR in one step using IRSA. Then Trivy scans the ECR image. Then we publish artifacts and a JSON pointer to S3. Then we verify ECR. Then on `development` branch, we trigger the CD job."

## Q2. Why ephemeral pods instead of a permanent Jenkins agent?

> "Three reasons: isolation, scaling, security. Isolation — each build starts clean, no state from the previous build. Scaling — builds are purely stateless workloads; Karpenter can spin up spot nodes in 40 seconds when demand spikes. Security — the build pod dies when the build ends. An attacker can't persist in a pod that doesn't exist."

## Q3. How do you handle AWS credentials in the pipeline?

> "We don't store AWS credentials in Jenkins. Every build pod runs as a ServiceAccount annotated with an IAM role ARN. The EKS pod-identity-webhook injects the IRSA token file and env vars. The AWS SDK picks them up automatically and calls `sts:AssumeRoleWithWebIdentity`. The only credential in Jenkins is the GitHub PAT for SCM checkout. All AWS operations are IRSA."

## Q4. What if a Trivy scan fails — how do you handle an unfixable CVE?

> "The process is: check if `--ignore-unfixed` would suppress it (no fix available yet). If yes, it's already ignored. If there's a fix and we're not applying it, I open a Jira ticket with the CVE ID, risk assessment, timeline to fix, and add it to `.trivyignore` with a comment pointing to the ticket and an expiry date. At the expiry date, the scan gate re-applies. We don't have a 'just disable the gate' option — that requires an explicit exception through the security team."

## Q5. How would you scale this to 50 services?

> "Four things: Job DSL or JCasC YAML generates the Jenkins jobs from a list — no manual job creation. The shared library means the pipeline template is already 3 lines per Jenkinsfile. One IRSA role can serve all 50 if they have identical blast radii. Karpenter with a CI-dedicated spot NodePool so compute scales automatically with demand. ECR repos and S3 paths follow a naming convention (`<project>-<env>-<service>`) so you can template Terraform. The pipeline itself is already parameterized by `SERVICE_NAME`."

## Q6. How does the Declarative pipeline `when` directive work?

> "Declarative evaluates `when` conditions before the stage runs. If the condition is false, the stage is skipped (not failed). `branch` works only in multibranch jobs — it reads `BRANCH_NAME`. `expression` is a Groovy closure that returns boolean. Common gotcha: `branch 'development'` doesn't work in a classic Pipeline job because `BRANCH_NAME` is null; use `expression { env.GIT_BRANCH?.contains('development') }` as the fallback."

## Q7. What is the difference between `wait: false` and `propagate: false` in `build job`?

> "`wait: false` — the current pipeline step returns immediately after triggering the downstream job; it doesn't block for the downstream result. `propagate: false` — even if the downstream job fails, that failure is not propagated upward to fail the current pipeline. We use both for the CI→CD trigger: CI's contract ends at 'image is in ECR.' A CD failure is visible on the CD job independently."

## Q8. Jenkins controller is on EC2, builds are on EKS. What are the failure modes?

> "Four main ones. (1) Network between EC2 and EKS API — controller can't spawn pods. Check SG rules on the EKS API endpoint, check the EC2 controller's VPC routing. (2) SA token expired or cluster CA rotated — after an EKS upgrade, re-issue the SA token. (3) EKS API overloaded or returning 429 — Kubernetes plugin has retry backoff, but a very busy cluster may need rate-limit tuning. (4) Spot node preempted mid-build — `restartPolicy: Never` means the pod stays in `Failed` state, the build is marked Aborted. Jenkins can be configured to retry the build or the pipeline can have retry logic."

## Q9. How do you prevent a build from poisoning the S3 cache?

> "Caches are saved **only on success** — the save step is in a `post { success { } }` block, not `always`. A failed build never overwrites the cache. The cache key includes the service name, so a broken `auth-service` build can't affect the `parent-comms` cache. If a cache somehow gets corrupted, we delete the S3 object (`aws s3 rm ...`) and the next build warms it cold. The `|| true` on restore means a missing cache is a non-fatal cold start, not a pipeline failure."

## Q10. What is JCasC and how does it fit here?

> "Jenkins Configuration as Code is a plugin that lets you define the entire Jenkins configuration (plugins, clouds, credentials, global settings) in a YAML file, stored in Git. The benefit: Jenkins controller can be destroyed and rebuilt from the YAML in minutes. Without JCasC, rebuilding Jenkins means manually re-entering every configuration. For our setup, JCasC would manage the K8s cloud config (API URL, namespace, SA token), the GitHub PAT credential, the shared library registration, and plugin versions — all reproducible, all auditable in Git."

## Q11. How do you debug a pipeline that succeeds locally but fails in CI?

> "Structured approach: (1) Check timestamps to find which exact stage and line. (2) For Maven failures, confirm `-am` flag for multi-module, confirm workspace structure is correct with `ls -la`. (3) For IRSA failures, add `aws sts get-caller-identity` and confirm the assumed role ARN. (4) For container failures, `kubectl logs -n jenkins-cicd-agents <pod> -c <container>` for the specific container. (5) For permission failures, `kubectl auth can-i <verb> <resource> -n <ns> --as system:serviceaccount:<ns>:<sa>`. (6) If you need to reproduce, spawn a debug pod: `kubectl run debug --image=maven:3.9 -n jenkins-cicd-agents --serviceAccount=jenkins-agent-builder -- sleep 3600` and exec in."

## Q12. Jenkins vs GitHub Actions vs GitLab CI — when do you choose Jenkins?

> "Jenkins: when you need self-hosted compute (data sovereignty, air-gapped), have complex plugin requirements, already have years of Jenkins job history and plugins, or need the Kubernetes plugin's ephemeral pod model on your own EKS cluster. GitHub Actions: simpler pipelines, team already in GitHub, managed runners, OIDC to AWS without a controller. GitLab CI: teams in GitLab, strong multi-env variable inheritance, native Kubernetes runner. The honest answer is: if starting greenfield today, GitHub Actions or GitLab CI are operationally lighter. Jenkins wins when you need full self-hosted control, the Kubernetes dynamic agent model, or you're in an enterprise with an existing Jenkins fleet."

---

# 11. Topics to Prepare (Complete Checklist)

## Core Jenkins

- [ ] Declarative vs Scripted Pipeline
- [ ] `agent { kubernetes { ... } }` — how pod allocation works
- [ ] `container('x')` — K8s exec mechanism, shared workspace volume
- [ ] `options { }` — timeout, timestamps, disableConcurrent, buildDiscarder
- [ ] `environment { }` — controller-side evaluation, `env.X = ...` pattern
- [ ] `when { }` — branch, expression, changeset, anyOf, not
- [ ] `post { always/success/failure/unstable }` — cleanup and notifications
- [ ] `parallel { }` — parallel stage execution
- [ ] `input` — manual approval gate, submitter restriction
- [ ] `script { }` — Declarative escape hatch
- [ ] `build job:` — trigger downstream, `wait`, `propagate`
- [ ] `@Library('...') _` — why the underscore, global pipeline library configuration
- [ ] Multibranch Pipeline vs Pipeline job — `BRANCH_NAME` vs `GIT_BRANCH`
- [ ] `archiveArtifacts`, `junit`, `stash`/`unstash`
- [ ] `currentBuild.currentResult`, `currentBuild.description`
- [ ] `credentials()` binding — `withCredentials`, `secretText`, `usernamePassword`

## Jenkins on Kubernetes

- [ ] Jenkins Kubernetes plugin — how it works (Fabric8 client, pod lifecycle)
- [ ] Controller-to-EKS auth: SA token vs exec credential plugin (and why we use SA token)
- [ ] Pod template — containers, workspace volume, serviceAccount, restartPolicy
- [ ] `defaultContainer` — what happens without `container('x')` wrapper
- [ ] IRSA in build pods — webhook injection, STS exchange, credential chain
- [ ] Pod OOMKilled / failed — what Jenkins sees, how to debug
- [ ] Pod pending — Karpenter/CA race, resource requests, node capacity
- [ ] Build pod isolation — one SA, namespace-scoped RBAC

## CI/CD Design

- [ ] CI/CD separation — why separate CI and CD pipelines
- [ ] Build-once, promote image by SHA — no rebuilds per env
- [ ] S3 pointer pattern — `latest/` vs `deployed/` per env per branch
- [ ] Immutable image tags — ECR IMMUTABLE repo setting
- [ ] Production approval gate — `input`, `submitter`, timeout
- [ ] Rollback via SHA parameter — not a special command
- [ ] DRY_RUN flag — `kubectl diff` before apply

## Security in Jenkins

- [ ] No docker.sock — Kaniko as the replacement
- [ ] IRSA — no static AWS keys, per-pod IAM
- [ ] Trivy secret scan — catches committed secrets
- [ ] Trivy fs vuln scan — catches dependency CVEs
- [ ] Trivy image scan — catches OS/runtime CVEs post-push
- [ ] `.trivyignore` + exception process — responsible CVE management
- [ ] Cosign image signing (roadmap)
- [ ] Least-privilege RBAC on CD pipeline (no secrets, no RBAC writes)
- [ ] Cross-account CI/CD (build account vs app account)

## Jenkins Architecture & Operations

- [ ] JCasC — Jenkins Configuration as Code, why it matters
- [ ] Jenkins HA — active/standby or active/active (JENKINS_URL, shared FS)
- [ ] Job DSL — generating jobs from code
- [ ] Shared library — structure, version pinning, tag vs branch
- [ ] Webhook setup — GitHub → Jenkins, HMAC secret, payload validation
- [ ] Caching strategies — S3 tarball, why not EBS/EFS/hostPath
- [ ] Build time profiling — timestamps, stage duration analysis
- [ ] Scale to N services — Job DSL, single IAM role, parameterized templates
- [ ] Blue-green Jenkins upgrade — running two controllers, migrating jobs
- [ ] Backup strategy — `jenkins_home` directory, JCasC YAML, job XML export

## Error Scenarios (all from §8 above)

- [ ] IRSA not picking up — missing SA annotation, pod not restarted
- [ ] ECR 403 — trust policy `sub` mismatch, wrong namespace
- [ ] Pods stuck Pending — memory overprovisioned, CA/Karpenter lag
- [ ] Maven build fail — missing `-am` for multi-module
- [ ] Pipeline hang — wrong container for tool (no `tar` in aws-cli image)
- [ ] Trivy false positive — skip `.git/`, `.trivyignore`
- [ ] CD rollout timeout — ECR eventual consistency race, verify-before-deploy
- [ ] Controller token expired after EKS upgrade — re-issue SA token

---

# 12. The 60-Second Closing Statement

> *"What I'm most proud of in the Jenkins work is the security posture. We went from a pipeline with a 2-year-old IAM key, docker socket mounted, no image scanning, and manual kubectl deploys — to a pipeline with zero static credentials, zero privileged containers, three automated CVE gates, and a full audit trail from commit to production deployment. Every design decision has a documented reason — IRSA over static keys, Kaniko over Docker, S3 tarballs over EBS PVCs, a separate CD job over mixed CI/CD. The shared library means 12 services share one pod template, and the CD pipeline means production rollback is a 2-minute, audited, automated operation — not a developer running kubectl on their laptop at 11pm."*

---

# 13. One-Liner Answers Cheat Sheet

| Question | One-liner |
|---|---|
| Where do builds run? | Ephemeral pods in `jenkins-cicd-agents` namespace on EKS |
| How does controller talk to EKS? | Static SA bearer token (Fabric8 K8s client doesn't support exec plugin) |
| How do pods talk to AWS? | IRSA → `AssumeRoleWithWebIdentity` → temp creds, auto-rotated |
| Image build engine? | Kaniko — no docker daemon, no `--privileged`, ECR auth via IRSA |
| Scanner? | Trivy — secret + dependency + image; exit-code 1 on HIGH/CRITICAL |
| Tag scheme? | `:<7-char git SHA>` (immutable) + `:<branch>-latest` (moving) |
| Shared across services? | `schoolspiderAgent()` in `jenkins-library/vars/` |
| Secrets in pipeline? | Only GitHub PAT for SCM. AWS = IRSA. App secrets never touch CI. |
| Cache strategy? | S3 tarball — multi-AZ, per-service Maven, shared Trivy DB |
| Why not EBS PVC for cache? | AZ-pinned, RWO blocks parallel builds, orphan volumes |
| CD trigger? | `build job: 'service-cd', wait: false, propagate: false` |
| Rollback? | Re-run CD with previous SHA as parameter |
| Production gate? | `input` step, `submitter` restricted, 30-min timeout |
| Build-once rule? | Same SHA image promoted across dev → qa → staging → prod. No rebuilds. |
| Build time? | ~3.5 min on warm cache (was 6 min) |
| How many services? | 12, all sharing the same library template |
