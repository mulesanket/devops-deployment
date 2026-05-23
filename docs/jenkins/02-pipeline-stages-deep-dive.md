# Jenkins Pipeline — Stage-by-Stage Deep Dive

> **Use this doc when:** The interviewer says *"Walk me through your Jenkinsfile, line by line."* This is the script.

The pipeline file lives at `application-backend/auth-service/Jenkinsfile`. Same template propagates to cart/order/product.

---

## 0. Anatomy at a Glance

```groovy
@Library('shopease-jenkins-library') _

pipeline {
    agent { kubernetes { yaml shopeaseAgent(serviceName: 'auth-service')
                         defaultContainer 'jnlp'
                         showRawYaml false } }
    options { ... }                  // build-level options
    environment { ... }              // build-level env vars
    stages {
        stage('Setup & Environment')       { container('aws')    { ... } }
        stage('Secret Scan')               { container('tools')  { ... } }
        stage('Dependency Vuln Scan')      { container('tools')  { ... } }
        stage('Build & Unit Tests')        { container('maven')  { ... } }
        stage('Image Build & Push')        { container('kaniko') { ... } }
        stage('Image Vulnerability Scan')  { container('tools')  { ... } }
        stage('Verify ECR Push')           { container('aws')    { ... } }
        stage('Build Summary')             { ... }
    }
}
```

---

## 1. The Header — `@Library` and `agent`

```groovy
@Library('shopease-jenkins-library') _
```

**Why the `_`?**
`@Library` is a Groovy *annotation* — it must annotate something. The underscore is a no-op identifier that satisfies the parser. Without it: compile error `Annotation must be on a class/method/field`.

**Where is the library actually configured?**
Jenkins → Manage Jenkins → System → Global Pipeline Libraries. Points at this same Git repo, default branch `development`, library root path `jenkins-library/`. Jenkins reads `jenkins-library/vars/shopeaseAgent.groovy` and exposes `shopeaseAgent(...)` as a global step.

```groovy
agent {
    kubernetes {
        yaml shopeaseAgent(serviceName: 'auth-service')
        defaultContainer 'jnlp'
        showRawYaml false
    }
}
```

| Sub-directive | Meaning |
|---|---|
| `yaml ...` | Pass a raw pod manifest. We pass the string our library returns. |
| `defaultContainer 'jnlp'` | If a `sh` step isn't wrapped in `container('x')`, it runs in `jnlp`. jnlp has `git`, `sh`, and the Jenkins agent process. |
| `showRawYaml false` | Suppresses dumping the resolved pod manifest into the build log on every run (useful during initial debug, noisy after). |

The `agent` is at the **pipeline level**, so one pod is allocated for the whole build. All stages reuse it. If `agent` were per-stage, we'd spawn 8 pods — slower and pointless here.

---

## 2. Options Block

```groovy
options {
    timeout(time: 30, unit: 'MINUTES')
    timestamps()
    disableConcurrentBuilds()
    ansiColor('xterm')
    buildDiscarder(logRotator(numToKeepStr: '5', daysToKeepStr: '30'))
}
```

| Option | What it does | Why I chose it |
|---|---|---|
| `timeout(30 MINUTES)` | Hard kill if build runs longer | Stuck `mvn` won't burn an executor forever |
| `timestamps()` | Prefixes every line with a wall clock | Critical for debugging stage timing |
| `disableConcurrentBuilds()` | Second push on same branch queues | Avoids two builds pushing the same SHA tag simultaneously |
| `ansiColor('xterm')` | Renders Jenkins-side ANSI escapes | Makes `logger.logSuccess('green')` show green |
| `buildDiscarder` | Auto-prunes old builds | Save Jenkins disk, keep last 5 / 30 days |

**Interview gotcha:** `ansiColor` only renders escapes that Jenkins itself emits via the AnsiColor plugin. Raw `\033[...]` inside a `sh ''' echo "\033[1;32m..." '''` will print literally unless you use `echo -e` or `printf`.

---

## 3. Environment Block

```groovy
environment {
    SERVICE_NAME  = 'auth-service'
    AWS_REGION    = 'ap-south-1'
    ECR_REPO_NAME = "shopease-webapp-development-${SERVICE_NAME}"
    SAFE_BRANCH   = "${env.BRANCH_NAME ?: env.GIT_BRANCH?.replaceAll('^origin/', '') ?: 'development'}"
}
```

**`SAFE_BRANCH` defensive ladder:**
1. `env.BRANCH_NAME` — set by *multibranch* pipeline jobs.
2. `env.GIT_BRANCH` — set by classic *pipeline* jobs (looks like `origin/development`; we strip the prefix).
3. `'development'` — literal fallback so we never produce `null-latest` as an image tag.

Groovy operators:
- `?:` Elvis — return left if truthy, else right.
- `?.` Safe navigation — return null if left is null, never NPE.

**Interview gotcha:** the `environment` block evaluates **on the controller**, not in the pod. That means `${env.BRANCH_NAME}` here resolves to whatever the controller sees. If you need a pod-side computation, do it inside a `script { }` block within a stage.

---

## 4. Stage 1 — Setup & Environment

```groovy
stage('Setup & Environment') {
    steps {
        container('aws') {
            script {
                logger.stageHeader('Setup & Environment')

                env.AWS_ACCOUNT_ID = sh(
                    script: 'aws sts get-caller-identity --query Account --output text',
                    returnStdout: true
                ).trim()

                env.ECR_REPO  = "${env.AWS_ACCOUNT_ID}.dkr.ecr.${env.AWS_REGION}.amazonaws.com/${env.ECR_REPO_NAME}"
                env.GIT_SHA   = (env.GIT_COMMIT ?: 'unknown').take(7)
                env.IMAGE_TAG = "${env.GIT_SHA}"
                env.IMAGE_URI = "${env.ECR_REPO}:${env.IMAGE_TAG}"

                // (logger.logInfo lines elided)

                sh '''
                    echo "-- IRSA caller identity (should be ci-agent-irsa) --"
                    aws sts get-caller-identity --output table
                '''

                logger.logSuccess('Setup & Environment completed')
            }
        }
    }
}
```

**Concept stack:**

| Construct | Meaning |
|---|---|
| `container('aws') { ... }` | Run nested steps inside the `aws` pod container. Plugin uses K8s exec (websocket). |
| `script { }` | Escape from Declarative into Scripted Pipeline (allows assignments, method calls). |
| `sh(script: '...', returnStdout: true)` | Run shell, capture stdout into a Groovy String. |
| `.trim()` | Remove trailing newline. |
| `env.GIT_COMMIT` | Auto-populated by the implicit `checkout scm` that Declarative runs before stages. |
| `.take(7)` | Groovy String method for short SHA. |

**Why call `aws sts get-caller-identity` twice (once for the account ID, once for proof)?**
The first one needs the value for tag construction. The second one prints the assumed-role ARN to the log — when reviewing a build, you immediately see whether IRSA wired up correctly. If you see the node's instance role instead, IRSA injection failed (usually missing SA annotation).

**Expected log line:**
```
Arn: arn:aws:sts::483829975256:assumed-role/shopease-webapp-development-ci-agent-irsa/botocore-session-...
```

---

## 5. Stage 2 — Secret Scan (Trivy)

```groovy
container('tools') {
    sh '''
        set -e
        trivy fs \
            --scanners secret \
            --exit-code 1 \
            --no-progress \
            --format json \
            --output "${WORKSPACE}/trivy-secrets-report.json" \
            "application-backend/${SERVICE_NAME}"

        trivy convert \
            --format table \
            "${WORKSPACE}/trivy-secrets-report.json"
    '''
}
```

**Important syntax distinction:**
- `'''...'''` triple-**single**-quoted: Groovy does *not* interpolate. `${WORKSPACE}` and `${SERVICE_NAME}` are read by the shell (they're real env vars on the pod, injected by Jenkins).
- `"""..."""` triple-**double**-quoted: Groovy interpolates *before* sending to shell. Use for messages, not for shell scripts (you'd have to double-escape `$`).

**Why I switched from gitleaks to Trivy `--scanners secret`?**
One tool for three scan modes: secret, fs vuln, image vuln. Fewer images to maintain, one consistent JSON schema, one place to learn flags. Trivy's secret scanner uses a regex pack (`secret-config` defaults) — comparable coverage to gitleaks for our use cases.

**`--exit-code 1` here**: trivy itself enforces the gate. If it finds anything, it returns 1, `set -e` aborts the shell, the stage fails, Jenkins fails the pipeline.

**`trivy convert --format table`**: re-renders the archived JSON into a human table for the console. Two-pass output: machine-readable JSON archived, human-readable table in the log.

---

## 6. Stage 3 — Dependency Vulnerability Scan (the "two-pass" pattern)

```groovy
# Pass 1: write JSON, never fail
trivy fs --scanners vuln --severity HIGH,CRITICAL --exit-code 0 --format json --output ...

# Pretty-print findings
trivy convert --format table --severity HIGH,CRITICAL ...

# Pass 2: same scan, enforce gate
trivy fs --scanners vuln --severity HIGH,CRITICAL --exit-code 1 --quiet ...
```

**Why two passes?**
- We want the **archived JSON to contain every finding** for forensics → first pass with `--exit-code 0`.
- We also want **the stage to fail** when there are HIGH/CRITICAL findings → second pass with `--exit-code 1`.
- `jq` isn't in the `aquasec/trivy` Alpine image, so I can't compute the count from JSON in shell.

**Solution:** run trivy twice. The second run is **fast** because the vuln DB is already on disk in `/root/.cache/trivy/`. This is a standard CI pattern — *report first, gate second*.

---

## 7. Stage 4 — Build & Unit Tests (Maven)

```groovy
container('maven') {
    sh '''
        set -e
        mvn -v
        cd application-backend
        mvn -pl ${SERVICE_NAME} -am -B -ntp verify
    '''
}
post {
    always {
        junit testResults: 'application-backend/**/target/surefire-reports/*.xml',
              allowEmptyResults: false,
              skipPublishingChecks: true
    }
}
```

**Maven flags:**

| Flag | Meaning |
|---|---|
| `-pl auth-service` | Build only this project |
| `-am` | "Also-make": include upstream dependencies (here, `shopease-common`) |
| `-B` | Batch mode (no interactive prompts, plain output) |
| `-ntp` | No transfer progress (suppresses download progress bars) |
| `verify` | Lifecycle phase past `test` and `package`; runs unit + integration tests + checks |

**`junit` step:**
- Parses surefire XML and populates Jenkins' "Test Result" widget.
- `allowEmptyResults: false` — a build with zero tests is treated as unstable.

**Important:** the `maven` container has its own `${HOME}/.m2` (currently `emptyDir`), so the first build of each pod redownloads all dependencies. With the EBS CSI driver installed we'd switch to a PVC — saves ~10–20s per build.

---

## 8. Stage 5 — Image Build & Push (Kaniko)

```groovy
container('kaniko') {
    sh '''
        set -e
        /kaniko/executor \
            --context "${WORKSPACE}/application-backend" \
            --dockerfile "${SERVICE_NAME}/Dockerfile" \
            --destination "${IMAGE_URI}" \
            --destination "${ECR_REPO}:${SAFE_BRANCH}-latest" \
            --label "git.sha=${GIT_SHA}" \
            --label "git.branch=${SAFE_BRANCH}" \
            --label "build.number=${BUILD_NUMBER}" \
            --label "service=${SERVICE_NAME}" \
            --snapshot-mode=redo \
            --use-new-run \
            --log-format=text \
            --verbosity=info
    '''
}
```

**Why Kaniko, not Docker?**
1. **Security:** no docker daemon to mount, no `--privileged` container, no node-root escape.
2. **ECR auth:** the `gcr.io/kaniko-project/executor:v1.23.2-debug` image ships with the AWS SDK. IRSA env vars (`AWS_ROLE_ARN`, `AWS_WEB_IDENTITY_TOKEN_FILE`) are auto-detected. No `aws ecr get-login-password | docker login` step.
3. **One step**: build + push in one invocation. The legacy pipeline had three: docker build, docker tag, docker push.

**Why two `--destination` flags?**

| Tag | Mutability | Use |
|---|---|---|
| `:90b581f` (short SHA) | **Immutable** — never overwritten | Exact identification for production; rollback target |
| `:development-latest` | **Moving** — always points at newest dev build | Convenience tag for dev environments |

**Other flags:**

| Flag | Meaning |
|---|---|
| `--context` | The build context root (what `docker build .` would see) |
| `--dockerfile` | Path **relative to context**: `auth-service/Dockerfile` from `application-backend` |
| `--snapshot-mode=redo` | More accurate filesystem snapshotting (handles edge cases for COPY) |
| `--use-new-run` | Newer concurrency model in kaniko — faster, less RAM |
| `--label` | OCI labels stamped on image config (audit trail in `aws ecr describe-images`) |

**Why no `--cache=true --cache-repo`?**
The cache repo (`shopease-webapp-development-auth-service-cache`) doesn't exist in ECR yet. Kaniko would create it on first push, but only if the IAM policy allows `ecr:CreateRepository`, which mine doesn't. **Trade-off**: skipping it costs ~30s per build but keeps the IAM policy tight. Adding the cache repo via Terraform is a Phase 2 item.

**ECR auth flow (no `docker login`):**
1. Kaniko sees `IMAGE_URI` is `*.dkr.ecr.*.amazonaws.com/...`.
2. It invokes the embedded AWS SDK.
3. SDK reads `AWS_ROLE_ARN` + `AWS_WEB_IDENTITY_TOKEN_FILE` (injected by the EKS pod-identity-webhook).
4. SDK calls STS `AssumeRoleWithWebIdentity`, gets temp creds.
5. SDK calls ECR `GetAuthorizationToken`, gets the docker-auth blob.
6. Kaniko uses that for the `PUT /v2/<repo>/manifests/<tag>` calls.

**Real log proof (build #63):**
```
time="..." level=info msg="Pushing image to 483829975256.dkr.ecr.ap-south-1.amazonaws.com/shopease-webapp-development-auth-service:90b581f"
time="..." level=info msg="Pushed ...@sha256:1b7a3f5a9416b3334737425f03f608090c14f5218bcc6cfa4bd2b15c3548d1c8"
```

---

## 9. Stage 6 — Image Vulnerability Scan

```groovy
container('tools') {
    retry(2) {
        sh '''
            set -e
            export TMPDIR="${WORKSPACE}/.trivy-tmp"
            mkdir -p "${TMPDIR}"
            trivy image \
                --scanners vuln \
                --severity HIGH,CRITICAL \
                --ignore-unfixed \
                --exit-code 0 \
                --no-progress \
                --format json \
                --output "${WORKSPACE}/trivy-image-report.json" \
                "${IMAGE_URI}"
        '''
    }

    sh '''
        # table render (no --exit-code)
        trivy convert --format table --severity HIGH,CRITICAL ...

        # gate (--exit-code 1)
        trivy image --scanners vuln --severity HIGH,CRITICAL --ignore-unfixed \
            --exit-code 1 --quiet "${IMAGE_URI}"
    '''
}
```

**Why scan **after** push, not before?**
1. We want to scan **the exact image** that's now in ECR — that's the source of truth, not the local tarball.
2. Trivy uses IRSA env vars to pull from ECR — no extra auth.
3. If push fails for IAM/network reasons, we fail-fast at Kaniko stage instead of wasting scan time.

**`--ignore-unfixed`** is the key tuning. CVEs without a fix in the upstream package can't be remediated by rebuilding — there's nothing to do but accept them. We only gate on CVEs that *have* a fixed version available, which means a Dockerfile base-image bump can remediate them.

**`retry(2)`** wraps the pull-and-scan in case ECR's API is briefly flaky or the Java DB download hiccups (it's ~50MB on first build).

**First-build cost:** ~57s (mostly Java DB download). Cached for 3 days. With persistent cache PVC, drops to ~10s.

---

## 10. Stage 7 — Verify ECR Push

```groovy
container('aws') {
    sh '''
        aws ecr describe-images \
            --repository-name "${ECR_REPO_NAME}" \
            --image-ids "imageTag=${GIT_SHA}" \
            --region "${AWS_REGION}"
    '''
}
```

**Belt-and-braces.** The push succeeded in Stage 5; this confirms ECR sees the image with the expected tag and dumps the manifest. Useful for catching:
- Immutable-tag policies silently rejecting a duplicate
- Regional misconfig (image pushed but to wrong region)
- Cross-account confusion in Phase 2

**Sample output:**
```json
{
    "imageDetails": [{
        "registryId": "483829975256",
        "repositoryName": "shopease-webapp-development-auth-service",
        "imageDigest": "sha256:1b7a3f5a9416b3334737425f03f608090c14f5218bcc6cfa4bd2b15c3548d1c8",
        "imageTags": ["90b581f", "development-latest"],
        "imageSizeInBytes": 139715430,
        "imagePushedAt": "2026-05-23T07:09:45.377000+00:00"
    }]
}
```

---

## 11. Stage 8 — Build Summary

```groovy
stage('Build Summary') {
    steps {
        script {
            echo """
============================================================
 BUILD SUMMARY
============================================================
 Service     : ${env.SERVICE_NAME}
 Branch      : ${env.BRANCH_NAME}
 Git SHA     : ${env.GIT_SHA}
 Image URI   : ${env.IMAGE_URI}
 Build #     : ${env.BUILD_NUMBER}
 Agent pod   : ${env.NODE_NAME}
 Result      : ${currentBuild.currentResult}
============================================================
"""
        }
    }
}
```

Plain Groovy `echo` of a multi-line string (`"""..."""` so Groovy interpolates the variables). The legacy pipeline also had `docker rmi` / `docker prune` here — **gone**, because the pod is destroyed when the build ends. No host cleanup needed. This is the elegance of the migration.

---

## 12. The Whole `post` Story

Each stage has its own `post { always { ... } success { ... } failure { ... } }`. Examples:

```groovy
post {
    always {
        archiveArtifacts artifacts: 'trivy-secrets-report.json',
                         allowEmptyArchive: true,
                         fingerprint: true
    }
    failure { script { logger.logError('Secret scan failed: secrets detected.') } }
}
```

A top-level `post` (not in our current version, but easy to add) is the right home for Slack notifications:

```groovy
post {
    success { slackSend channel: '#ci', message: "✅ ${env.SERVICE_NAME} #${env.BUILD_NUMBER}" }
    failure { slackSend channel: '#ci', message: "❌ ${env.SERVICE_NAME} #${env.BUILD_NUMBER} ${env.BUILD_URL}" }
}
```

---

## 13. The Pod YAML (Shared Library)

From `jenkins-library/vars/shopeaseAgent.groovy`, abbreviated:

```yaml
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-agent-builder      # ⟵ IRSA target
  restartPolicy: Never
  terminationGracePeriodSeconds: 10
  securityContext:
    runAsUser: 0                                 # Kaniko needs root
    fsGroup: 0
  containers:
    - name: jnlp
      image: jenkins/inbound-agent:latest
      resources: { requests: {cpu: 50m, memory: 128Mi}, limits: {cpu: 500m, memory: 512Mi} }
      volumeMounts:
        - { name: workspace-volume, mountPath: /home/jenkins/agent }

    - name: maven
      image: maven:3.9-eclipse-temurin-21
      command: ["sleep"]                          # ⟵ override entrypoint
      args: ["infinity"]                          #     so the container stays up
      tty: true
      resources: { requests: {cpu: 100m, memory: 512Mi}, limits: {cpu: 2, memory: 2Gi} }
      volumeMounts:
        - { name: workspace-volume, mountPath: /home/jenkins/agent }
        - { name: maven-cache,      mountPath: /root/.m2 }

    - name: kaniko
      image: gcr.io/kaniko-project/executor:v1.23.2-debug
      command: ["sleep"]
      args: ["infinity"]
      env:
        - { name: AWS_SDK_LOAD_CONFIG, value: "true" }
      volumeMounts:
        - { name: workspace-volume, mountPath: /home/jenkins/agent }
        - { name: kaniko-cache,     mountPath: /kaniko/.cache }

    - name: aws
      image: amazon/aws-cli:2.17.18
      command: ["sleep"]
      args: ["infinity"]

    - name: tools
      image: aquasec/trivy:0.55.0
      command: ["sleep"]
      args: ["infinity"]

  volumes:
    - { name: workspace-volume, emptyDir: {} }
    - { name: maven-cache,      emptyDir: {} }
    - { name: kaniko-cache,     emptyDir: {} }
```

**Key invariants:**

1. **All non-jnlp containers run `sleep infinity`.** Otherwise their entrypoint would exit and the container would die, breaking `kubectl exec` from Jenkins.
2. **One workspace volume mounted at the same path everywhere.** When stage 4 builds the jar in `/home/jenkins/agent/workspace/.../auth-service/target/`, stage 5's kaniko sees it at the same path.
3. **Sum of requests = 350m CPU / 1.5Gi mem.** Scheduler-friendly on `t3.medium` nodes (1.93 CPU usable, 3.9Gi mem). Limits can burst higher.
4. **`runAsUser: 0`.** Required by Kaniko (it does chroot-style operations). Trade-off accepted because we're in an isolated namespace with no host paths mounted.

---

## 14. Failure Modes & How To Recognize Them

| Symptom in log | Root cause |
|---|---|
| `Pod [Pending][ContainersNotReady]` forever | Scheduling failure: no node capacity, or image pull failing. `kubectl describe pod -n jenkins-cicd-agents <name>` confirms. |
| `git: command not found` in `aws` container | Tried to shell out to git from a non-git image. Use `env.GIT_COMMIT` from `checkout scm`. |
| `unknown flag: --scanners` on `trivy convert` | `--scanners` is a *scan* flag, not a *convert* flag. Convert just re-formats existing JSON. |
| `multiple targets cannot be specified` from trivy | Missing newline between `trivy fs \ ... "path"` and the next `echo` — shell read echo as an extra positional arg. |
| `Pushing image ... DENIED` from kaniko | IAM policy missing an ECR action, or trust policy `sub` typo, or the resource ARN doesn't match the repo name. |
| `script returned exit code 127` | "command not found" — the container doesn't have the tool you're invoking. Check `which` inside the container interactively. |

---

## 15. Stage-by-Stage Timing (Build #63, warm node)

| Stage | Time | Notes |
|---|---|---|
| Pod allocation | ~30s | ContainerCreating across 5 images. Warm node saves image-pull time. |
| 1. Setup | 4s | Two `aws sts` calls + Groovy bookkeeping. |
| 2. Secret Scan | 5s | Trivy secret pack is local; very fast. |
| 3. Dep Vuln Scan | 16s | First-build vuln DB download (~10s). Subsequent builds: ~5s. |
| 4. Build & Unit Tests | 35s | mvn verify, 12 tests, ~30 deps downloaded into emptyDir. |
| 5. Image Build & Push | 9s | Kaniko build + 2-tag push. |
| 6. Image Vuln Scan | 57s | **Java DB download (~45s)**. Cached 3 days. With PVC: ~10s. |
| 7. Verify ECR | 1s | One `aws ecr describe-images` call. |
| 8. Summary | <1s | Plain `echo`. |
| **Total** | **~2m 12s** | |

---

## 16. The Two-Sentence Stage Summaries (Mnemonic for Interview)

| # | Stage | Two-sentence pitch |
|---|---|---|
| 1 | Setup & Environment | Confirms IRSA is working by running `aws sts get-caller-identity`. Builds `IMAGE_URI` from `env.GIT_COMMIT.take(7)` so we never need git in the aws container. |
| 2 | Secret Scan | Trivy with the `secret` scanner runs against the service source. Gates on any finding via `--exit-code 1`. |
| 3 | Dependency Vuln Scan | Trivy `fs` scan with HIGH/CRITICAL gate; two-pass pattern (report-then-gate) because the trivy Alpine image has no `jq`. |
| 4 | Build & Unit Tests | `mvn -pl auth-service -am -B verify` inside the maven container. Surefire XML + JaCoCo XML archived and published. |
| 5 | Image Build & Push | Kaniko builds & pushes in one step. Two tags: immutable short-SHA and moving `branch-latest`. ECR auth is pure IRSA, no `docker login`. |
| 6 | Image Vuln Scan | Trivy `image` against the just-pushed ECR URI, `--ignore-unfixed` so we only gate on fixable CVEs. Runs after push so we scan the canonical image. |
| 7 | Verify ECR Push | `aws ecr describe-images` as a sanity check — catches immutable-tag rejections and regional misconfig. |
| 8 | Build Summary | Plain `echo` of metadata. No host cleanup needed — the pod is destroyed automatically. |
