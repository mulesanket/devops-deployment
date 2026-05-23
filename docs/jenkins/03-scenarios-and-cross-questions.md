# Jenkins CI/CD — Scenarios, Cross-Questions, Whiteboard Drills

> **Use this doc when:** Preparing for the senior part of an interview where they probe how the system behaves under failure, how it scales, and how you'd extend it. 3-YOE-strong answers below; memorize the **bold one-liners**.

---

## Section A. Foundational ("Describe what you built")

### A1. "Walk me through your CI pipeline."

> **"A push to GitHub triggers a Jenkins job on a controller that only orchestrates. The Kubernetes plugin creates an ephemeral pod in the `jenkins-cicd-agents` namespace on our EKS cluster. The pod has five containers — jnlp, maven, kaniko, aws, tools — sharing one workspace volume. The pipeline has 8 stages: setup, secret scan, dependency vuln scan, build & test, Kaniko image build+push, image vuln scan, ECR verify, summary. AWS access uses IRSA — the pod's ServiceAccount is annotated with an IAM role, the EKS pod-identity-webhook injects an OIDC token, the AWS SDK exchanges it at STS. When the build ends, the pod is destroyed."**

Pause. Let them ask follow-ups. The most common ones:

### A2. "Why did you move builds off the Jenkins controller?"

> **"Three reasons: blast radius, scaling, isolation."**
> *Blast radius* — on the controller, every build shared the same docker daemon, the same `.m2`, the same installed tools. One bad PR could poison the cache for the next 100 builds.
> *Scaling* — builds on EKS get a fresh pod every time. Scale out by adding nodes; doesn't touch the controller.
> *Isolation* — the controller used to need `docker.sock` mounted, which is effectively node root. Kaniko on EKS needs zero socket mounts. We closed a known privesc path.

### A3. "Why Kaniko instead of Docker?"

> **"Daemonless image builds + IRSA-native ECR auth."**
> Two reasons:
> 1. **Security.** Running docker inside K8s needs `--privileged` or `docker.sock` mount. Either gives a build effective root on the node. Kaniko is userspace tar/extract operations against an overlay FS — zero daemon, zero socket.
> 2. **Auth.** Kaniko's `-debug` image ships with the AWS SDK. IRSA env vars are picked up automatically, so there's no `aws ecr get-login-password | docker login` step. We dropped a whole stage and a credential round-trip.

**Trade-off they'll probe:** *"What about BuildKit?"* — Buildkit-on-K8s is also valid; faster for huge multi-stage builds because of its DAG cache. For Spring Boot images (one COPY of a fat JAR) the speedup is marginal. We'd consider it if our images grow.

### A4. "How does the Jenkins controller authenticate to EKS?"

> **"Static ServiceAccount bearer token, not `aws eks get-token`, because the Fabric8 K8s client used by the Jenkins plugin doesn't support exec-plugin credentials."**

Concrete chain:
1. SA `jenkins-controller` in namespace `jenkins-cicd-agents`.
2. A long-lived Secret of type `kubernetes.io/service-account-token` bound to it.
3. A RoleBinding granting that SA `pods`, `pods/exec`, `pods/log`, `secrets` (read) on the namespace.
4. The token is stored in Jenkins → Credentials as Secret Text.
5. The Cloud config in Jenkins points at the EKS API endpoint with that credential.

**They'll push:** *"Isn't a static token a security smell?"* — yes, that's a known gap. Mitigations: namespace-scoped RBAC (Role, not ClusterRole), rotation runbook, OIDC federation roadmap (Phase 2). Kubernetes 1.24+ supports `TokenRequest` for time-bound tokens, but the plugin version we run needs the token in a credential — operationally messier than the static Secret today.

---

## Section B. IRSA Deep-Dives

### B1. "Draw the IRSA trust policy on the whiteboard."

```json
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": {
      "Federated": "arn:aws:iam::483829975256:oidc-provider/oidc.eks.ap-south-1.amazonaws.com/id/63D158CCDA9F25D6B374AB26605FF873"
    },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "oidc.eks.ap-south-1.amazonaws.com/id/63D158...:sub": "system:serviceaccount:jenkins-cicd-agents:jenkins-agent-builder",
        "oidc.eks.ap-south-1.amazonaws.com/id/63D158...:aud": "sts.amazonaws.com"
      }
    }
  }]
}
```

**Three things to highlight:**
1. **`Federated` principal**: the OIDC provider, *not* an account or user.
2. **`sub` condition**: pins the role to one specific SA in one specific namespace. Format: `system:serviceaccount:<ns>:<sa>`.
3. **`aud` condition**: the audience claim must match `sts.amazonaws.com`. EKS sets it that way by default. Without this check, a stolen token from another consumer could assume the role.

### B2. "Walk me through what happens when `aws sts get-caller-identity` runs inside a build pod."

1. Pod starts. **EKS pod-identity-webhook** (admission controller) sees the SA has annotation `eks.amazonaws.com/role-arn`, mutates the pod spec:
   - Adds env `AWS_ROLE_ARN=arn:aws:iam::483829975256:role/shopease-webapp-development-ci-agent-irsa`
   - Adds env `AWS_WEB_IDENTITY_TOKEN_FILE=/var/run/secrets/eks.amazonaws.com/serviceaccount/token`
   - Mounts a projected volume that *kubelet* refreshes with a fresh 1-hour-lifetime SA token.
2. `aws sts get-caller-identity` triggers the AWS SDK's credential chain.
3. SDK finds the env vars → calls STS `AssumeRoleWithWebIdentity` with the token file's contents as the `WebIdentityToken` parameter.
4. STS validates the token's signature against the OIDC provider's JWKS endpoint, checks `sub` and `aud` against the role's trust policy.
5. STS returns temp credentials (`AccessKeyId`, `SecretAccessKey`, `SessionToken`) with TTL ~1 hour.
6. SDK uses those for the actual `sts:GetCallerIdentity` call (or any other AWS API).
7. SDK auto-refreshes when credentials are within 5 minutes of expiry — the kubelet has by then refreshed the SA token too.

**The line in our log proving all this worked:**
```
Arn: arn:aws:sts::483829975256:assumed-role/shopease-webapp-development-ci-agent-irsa/botocore-session-1779520115
```

### B3. "Why is the IAM policy scoped to `shopease-webapp-development-*`?"

> **"Least privilege; the dev pipeline can't push to production repos. Critical for the build/app account split we're moving to."**

The resource ARN pattern in `ci-cd.tf`:
```
arn:aws:ecr:ap-south-1:483829975256:repository/shopease-webapp-development-*
```

This means `ecr:PutImage` succeeds against `shopease-webapp-development-auth-service` but returns AccessDenied against `shopease-webapp-production-auth-service`, even though both are in the same account. When we cross-account in Phase 2, the principle stays the same — the build-account role only gets `sts:AssumeRole` to one narrow role in the app account.

---

## Section C. Failure-Mode Scenarios

### C1. "A build pod is stuck in `Pending` forever. How do you debug?"

```
kubectl get pod -n jenkins-cicd-agents
kubectl describe pod -n jenkins-cicd-agents <pod-name>
```

**Two common scenarios** to call out:

**Scenario 1: `FailedScheduling 0/2 nodes are available`**
- Check requests vs node capacity (`kubectl describe node`).
- Check if Cluster Autoscaler / Karpenter is running and able to scale.
- If on a small dev cluster, lower the maven request from 100m to 50m, or temporarily switch to a single-container smoke-test pod.

**Scenario 2: `ImagePullBackOff` on kaniko or trivy image**
- Usually Docker Hub or gcr.io rate-limit when the node is cold.
- Mitigation: mirror the kaniko / trivy / maven images to ECR Public or a private ECR repo and update the pod template.
- Quick fix: kick the node (cordon + drain) so the pod retries on a different one.

### C2. "ECR push returns 403 DENIED. Where do you look?"

**Four-layer onion**, in this order:

1. **IAM identity policy** on the IRSA role. Required actions:
   - `ecr:GetAuthorizationToken` (on `*`)
   - `ecr:BatchCheckLayerAvailability`
   - `ecr:InitiateLayerUpload`
   - `ecr:UploadLayerPart`
   - `ecr:CompleteLayerUpload`
   - `ecr:PutImage`
   Missing any one breaks the push at that specific HTTP call.
2. **Resource ARN pattern**. Easy to drop a wildcard or get the region/account wrong. The pattern must match the actual repo.
3. **Trust policy** on the role. If the `sub` is one character off (`jenkins-agent-build` vs `jenkins-agent-builder`), the AssumeRole fails silently in the SDK with an unhelpful error. Check it character-by-character.
4. **ECR repository policy** (rare for single-account). In cross-account, the repo's resource policy *must* explicitly allow the role.

**Quick sanity check inside the pod:**
```sh
aws sts get-caller-identity        # should say assumed-role/ci-agent-irsa/...
aws ecr describe-repositories --region ap-south-1 | head -20
aws ecr get-login-password --region ap-south-1 >/dev/null && echo "GetAuthToken OK"
```

### C3. "Tests pass locally but fail in CI. Common causes?"

| Cause | Fix |
|---|---|
| **Module ordering** — service can't compile because `shopease-common.jar` isn't in the local repo | `mvn -pl auth-service -am` (the `-am` flag = "also-make dependencies") |
| **Testcontainers / Docker-dependent tests** — no docker daemon in the pod | Replace with Spring Boot test slices (e.g., `@DataJpaTest` against H2), or move those tests to a deployed-env smoke stage |
| **Time zones / locales** — CI pod is UTC, dev laptop is IST | Set `-Duser.timezone=UTC` in `mvn` invocation; assert dates in UTC |
| **Resource limits** — JVM heap collides with container memory limit | `-XX:MaxRAMPercentage=75.0` (already in our Dockerfile); pod memory limit raised to 2Gi |
| **Network egress** — CI pod can't reach Maven Central | (Unlikely on our setup — NAT works.) Check NetworkPolicy if you add one. |

### C4. "The build hangs at `Building stage 'eclipse-temurin:21-jre-alpine'`."

That's Kaniko unpacking the base image. If it actually hangs (not just slow):
- Disk pressure on the node — kaniko fails to write to `/kaniko/0/`. `kubectl describe node` + check disk-pressure conditions.
- Out of inodes (the layer has many tiny files). Same diagnostic.
- A corrupted layer in the Docker Hub manifest — switch to a digest pin (`eclipse-temurin@sha256:...`) instead of a tag.

### C5. "A pod gets OOMKilled mid-Maven build. What now?"

The pod's `restartPolicy: Never` means Jenkins sees the agent disconnect, marks the build aborted. Pod sticks around in `Failed` state for post-mortem.

```sh
kubectl describe pod -n jenkins-cicd-agents <pod>
# Look for "OOMKilled" in the maven container's Last State

kubectl logs -n jenkins-cicd-agents <pod> -c maven --previous
```

Then:
- Raise maven memory limit in `shopeaseAgent.groovy` (currently 2Gi → bump to 3Gi).
- Add JVM heap pressure flag to `mvn`: `MAVEN_OPTS="-Xmx2g" mvn ...`.
- Investigate whether a test is leaking — heap-dump on OOM is a useful flag to bake in.

---

## Section D. Optimization & Scale Questions

### D1. "How do you make this pipeline faster?"

**My answer follows a profiling order:**

1. **Persistent caches** via EBS CSI PVCs:
   - `/root/.m2` (Maven deps) — saves ~10s per build after first
   - `/kaniko/.cache` (Kaniko snapshot cache) — saves ~5s
   - `/root/.cache/trivy` (Trivy DB) — saves ~50s on first scan
   Today these are all `emptyDir` because we haven't installed the EBS CSI driver. One-line Terraform change.

2. **Kaniko `--cache-repo`** pointing at a dedicated ECR repo. Skips most COPY/RUN steps on warm builds. Requires an extra ECR repo per service (or one shared one).

3. **Parallel stages**: Secret scan, dep vuln scan, and image-build are all independent — could run in `parallel { }`. Saves ~25s.

4. **Skip scans on docs-only PRs.** A simple `when { changeset ... }` filter.

5. **Reuse pods across stages.** Already do this; one pod for the whole build.

**The "but":** A 2-minute build is already cheap relative to a 15-minute build for a real codebase. I'd profile real bottlenecks before optimizing.

### D2. "How would this scale to 100 services?"

Three things grow linearly:

| Thing | Today | At 100 services |
|---|---|---|
| ECR repos | 4 | 100 — created by a Terraform module |
| Jenkins jobs | 4 manual | Job DSL or JCasC YAML generates them from a list |
| IRSA roles | 1 shared | Probably still 1 unless services have wildly different blast radii |
| Service Jenkinsfiles | 4 (copy-paste) | Generated from a single template via Job DSL, OR turned into a single `shopeasePipeline(serviceName: 'x')` library call so each Jenkinsfile is 3 lines |

**Cluster sizing:** I'd use Karpenter with a spot-only NodePool dedicated to CI. Build pods are stateless and short-lived — perfect for spot. If a node is reclaimed, the build retries.

### D3. "How do you roll out a change to the pod template safely?"

**Three-step canary:**
1. Edit `jenkins-library/vars/shopeaseAgent.groovy` on a feature branch.
2. Point our `eks-agent-smoke-test` Jenkins job at that branch via `@Library('shopease-jenkins-library@feature/x') _`.
3. Once green, merge to `development` — propagates to all four services automatically.

**Risk:** a bad change in the library breaks all services simultaneously. For sensitive changes I'd:
- Pin individual services to a tagged release of the library (`@v1.2.3`) while the others stay on `development`.
- Promote one service to `@development` first, observe, then promote the rest.

---

## Section E. Security Questions

### E1. "How do you prevent supply-chain attacks?"

**Today (in place):**
- **Trivy secret scan** — catches keys committed by accident.
- **Trivy `fs` vuln scan** — catches known-CVE deps in `pom.xml`.
- **Trivy `image` vuln scan** — catches CVEs in OS packages and JAR contents post-build.
- All three gate on HIGH/CRITICAL.

**Roadmap:**
- **Cosign + keyless OIDC** for image signing. Image push includes a signature.
- **SBOM generation** with `trivy sbom` archived alongside the image.
- **Admission control** (Kyverno or OPA Gatekeeper) on the app clusters that rejects unsigned images or images that have HIGH/CRITICAL CVEs found post-deployment.

### E2. "A build pod gets compromised. What's the blast radius?"

**What the attacker can do:**
- Push images to ECR repos matching `shopease-webapp-development-*` (mitigation: ECR immutable tags, image signing, scan-on-push).
- Read/write the one S3 bucket `shopease-webapp-development-ci-artifacts` (mitigation: versioning, lifecycle to delete after 90d, KMS in roadmap).
- Make outbound network calls to anywhere (mitigation: NetworkPolicy restricting egress to ECR, GitHub, Docker Hub, Maven Central).

**What they cannot do:**
- Assume any role in the production account (cross-account trust policy required, doesn't exist).
- Read production secrets (RBAC scoped to `jenkins-cicd-agents` namespace).
- Modify the Jenkins controller (different VM, different network segment).
- Modify other namespaces in the build cluster.

**Detection:** CloudTrail logs every `AssumeRoleWithWebIdentity`. Anomalous source IPs or unusual API calls trigger GuardDuty findings.

### E3. "How do you handle secrets in the pipeline?"

> **"AWS credentials don't exist in the pipeline — IRSA handles AWS. The only secret in Jenkins is `jenkins-github-pat` for SCM checkout."**

For application-side secrets (DB passwords, JWT signing keys, etc.):
- They live in **AWS Secrets Manager**, encrypted with a KMS CMK.
- App pods read them via **External Secrets Operator (ESO)**, which uses its own IRSA role.
- The build pipeline never touches them. CI only builds images; secrets are injected at deploy time.

---

## Section F. The Gotcha Questions (Senior-Track)

### F1. "Why does `defaultContainer 'jnlp'` matter?"

> **"jnlp is what holds the agent process and the websocket back to the controller. Every other container is in `sleep infinity` and only useful when we explicitly target it with `container('x')`."**

The K8s plugin runs the Jenkins agent JAR in the jnlp container. When you write `sh 'foo'`, the plugin POSTs an `exec` request to `/api/v1/.../pods/<pod>/exec?container=jnlp`. The `container('maven') { sh 'foo' }` step re-targets that exec to `?container=maven`. Same workspace volume, different process namespace.

### F2. "Why is `GIT_SHA = env.GIT_COMMIT.take(7)` better than shelling out to `git`?"

> **"Three reasons: speed, portability, container minimalism."**

- **Speed**: Groovy evaluation vs subprocess fork.
- **Portability**: works even in containers that don't have git installed (like `amazon/aws-cli:2.17.18` — that's what bit us in build #61).
- **Container minimalism**: we don't have to ship git in every tool container "just in case." Each image stays focused on its one job.

`env.GIT_COMMIT` is populated by Declarative's implicit `checkout scm` before stages start.

### F3. "Why two `--destination` flags on Kaniko?"

> **"Immutable for traceability, moving for convenience."**

| Tag | Mutability | Consumer |
|---|---|---|
| `:90b581f` (short SHA) | **Never overwritten** | Production deployments, rollback targets |
| `:development-latest` | **Always points at the newest dev build** | Dev environments, `kubectl set image ... :development-latest && rollout restart` |

Senior interviewer will probe: *"How do you enforce that production never references `:development-latest`?"* — Answer: Kustomize / Helm value layers pin to a SHA; the value file is the source of truth and is reviewed in Git.

### F4. "What does `disableConcurrentBuilds()` actually prevent?"

> **"Two pushes on the same branch back-to-back would otherwise start two builds racing on the same Git SHA — both trying to push the same image tag and update `:development-latest`. The second waits for the first."**

Trade-off: throughput on noisy branches. Alternative: ECR immutable tags (set at the repo level via Terraform) reject the duplicate at the registry. Both belt-and-braces is fine.

### F5. "Why is `runAsUser: 0` on the pod, isn't that bad?"

> **"Kaniko needs root inside the container for chroot-like operations. The pod is in a dedicated namespace with no host paths mounted and no `--privileged`. Root inside the container is not root on the node — that's the line that matters."**

Mitigations:
- No `hostPath` volumes, no `hostNetwork`, no `hostPID`.
- The namespace has a `LimitRange` and `ResourceQuota` (TODO — Phase 2).
- NetworkPolicy restricts egress (TODO — Phase 2).
- Pod runs as a SA whose RBAC is read-only on resources outside the namespace.

If we wanted non-root: split the pod, run kaniko in a sidecar as root, maven/aws/tools as non-root. Adds complexity without much real-world security gain.

### F6. "What's `set -e` and why use it everywhere?"

> **"Bash's default behavior is to continue past failed commands. `set -e` makes the script abort on the first non-zero exit. Critical in CI."**

Without `set -e`:
```sh
trivy fs --exit-code 1 secret-scan ...   # fails, returns 1
echo "Scan passed"                       # runs anyway!
                                          # script exits 0 because echo succeeded
```

With `set -e`:
```sh
trivy fs --exit-code 1 ...               # fails, returns 1
                                          # script aborts here
echo "Scan passed"                       # never runs
```

Jenkins sees the non-zero exit, fails the `sh` step, fails the stage.

### F7. "Why archive `*.json` reports if no one reads them?"

> **"Forensics after the fact, and machine-readable consumption by other tools."**

A `trivy-fs-report.json` archived on each build means:
- A new vulnerability scoring system (e.g., EPSS becomes severity-mapping criterion) — we can re-process historical reports without re-scanning.
- We can pipe last-90-days reports into a dashboard.
- An auditor can ask "show me what was found on build N" — answer is one URL away.

Cost: ~50KB per build × 5 retained builds × 4 services = 1 MB of archive storage. Trivial.

### F8. "Why `retry(2)` on the image vuln scan?"

> **"Two flake sources: ECR API momentary 5xx, and Trivy's Java DB download (~50MB on first run) hitting GitHub's transient errors."**

Both are recoverable on retry. We don't retry the Kaniko push because a real auth failure should fail fast — retrying just delays the inevitable.

---

## Section G. Whiteboard Drills (Practice These)

### G1. Draw the IRSA flow end-to-end

```
Pod              Webhook         Kubelet          AWS SDK         STS              AssumeRoleWithWebIdentity
 │                  │                │                │              │
 ├ created with ────►                │                │              │
 │ SA "jenkins-     ├ mutates ───────►                │              │
 │ agent-builder"   │ pod spec       │                │              │
 │                  │ (env + vol)    │                │              │
 │                  │                ├ mounts ────────►              │
 │                  │                │ projected      │              │
 │                  │                │ token (1h TTL) │              │
 │                                                    ├ reads env ───►
 │                                                    │ AWS_ROLE_ARN  │
 │                                                    │ AWS_WEB_      │
 │                                                    │ IDENTITY_     │
 │                                                    │ TOKEN_FILE    │
 │                                                    │               │
 │                                                    ├ calls ────────► AssumeRoleWithWebIdentity
 │                                                    │               │ - validates token sig
 │                                                    │               │ - checks sub & aud
 │                                                    │               │ - issues temp creds
 │                                                    │◄──────────────┤ AccessKey+Secret+Session
 │                                                    │               │
 │                                                    ├ uses creds ───► any AWS API
```

### G2. Draw the build pod and label its parts

```
┌──────────────────── Pod (auth-service build #63) ──────────────────────┐
│ namespace: jenkins-cicd-agents                                         │
│ serviceAccountName: jenkins-agent-builder                              │
│ restartPolicy: Never                                                   │
│ securityContext: runAsUser=0, fsGroup=0                                │
│                                                                        │
│  ┌─────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐ │
│  │ jnlp    │  │ maven    │  │ kaniko   │  │ aws      │  │ tools    │ │
│  │ image:  │  │ 3.9-     │  │ executor │  │ aws-cli  │  │ trivy    │ │
│  │ jenkins │  │ jdk21    │  │ v1.23.2  │  │ 2.17.18  │  │ 0.55.0   │ │
│  │ inbound │  │          │  │ -debug   │  │          │  │          │ │
│  │ agent   │  │          │  │          │  │          │  │          │ │
│  └────┬────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘ │
│       │            │              │              │              │       │
│       └────────────┴──────────────┴──────────────┴──────────────┘       │
│                                  │                                       │
│  Shared volumes:                  ▼                                       │
│   workspace-volume (emptyDir) → /home/jenkins/agent                       │
│   maven-cache    (emptyDir)   → /root/.m2 (maven only)                    │
│   kaniko-cache   (emptyDir)   → /kaniko/.cache (kaniko only)              │
│                                                                          │
│  IRSA-injected env (all containers):                                     │
│   AWS_ROLE_ARN=arn:aws:iam::483829975256:role/...ci-agent-irsa           │
│   AWS_WEB_IDENTITY_TOKEN_FILE=/var/run/secrets/.../token                 │
└─────────────────────────────────────────────────────────────────────────┘
```

### G3. Sketch the controller → pod handshake

```
Jenkins controller (EC2)                       EKS API server                 Pod
       │                                              │                        │
       │ 1. POST /api/v1/namespaces/                  │                        │
       │    jenkins-cicd-agents/pods                  │                        │
       │    Authorization: Bearer <static-SA-token>   │                        │
       ├─────────────────────────────────────────────►│                        │
       │                                              │ 2. Validates token,    │
       │                                              │    checks RBAC, creates│
       │                                              │    Pod object          │
       │                                              ├───────────────────────►│
       │                                              │                        │ 3. kubelet
       │                                              │                        │    pulls images,
       │                                              │                        │    starts containers
       │                                              │                        │
       │ 4. Pod's jnlp container starts, reads:       │                        │
       │    JENKINS_URL=http://controller:8080/       │                        │
       │    JENKINS_SECRET=<one-time agent token>     │                        │
       │◄─────────────────────────────────────────────┼────────────────────────┤
       │ 5. Websocket connection established;         │                        │
       │    agent registers as a Jenkins node         │                        │
       │                                              │                        │
       │ 6. For each `sh` step in a stage:            │                        │
       │    POST /api/v1/.../pods/<pod>/exec          │                        │
       │      ?container=<name>                       │                        │
       │      &command=sh&command=-c&command=...      │                        │
       ├─────────────────────────────────────────────►│                        │
       │                                              ├──── exec ─────────────►│
       │                                              │                        │ runs command
       │                                              │◄─── stdout/stderr ─────┤
       │◄─────────────────────────────────────────────┤                        │
       │ 7. When pipeline completes, controller       │                        │
       │    deletes the Pod object                    │                        │
       ├─────────────────────────────────────────────►│                        │
       │                                              ├──── terminate ────────►│
       │                                              │                        │ kubelet
       │                                              │                        │ removes pod
```

---

## Section H. The "Tell Me About a Bug You Hit" Story

> *"Build #61 failed at the Setup stage with `git: command not found`. The aws-cli image (`amazon/aws-cli:2.17.18`) is a minimal Alpine — no git. I'd written `env.GIT_SHA = sh(script: 'git rev-parse --short=7 HEAD', returnStdout: true).trim()`, but that ran inside `container('aws')` which had no git. The fix was to read `env.GIT_COMMIT` instead — it's populated by Declarative's implicit `checkout scm` before stages start. Took 2 minutes to debug, 30 seconds to fix, one commit. It taught me two things: (1) the `checkout scm` populates handy env vars and you should prefer them over shelling out; (2) every container in the pod is minimal — don't assume `git`, `jq`, `curl`, anything is there. The container should match the tool: if you need git, use jnlp or maven; if you need aws, use aws."*

This is exactly the kind of story interviewers love — concrete bug, root cause, fix, lesson learned. Have a couple of these ready.

---

## Section I. Quick-Recall Cheat Sheet

| Question | One-liner |
|---|---|
| Where do builds run? | Ephemeral pods in EKS `jenkins-cicd-agents` namespace |
| Why not on controller? | Blast radius, scale, isolation, no docker.sock |
| Why Kaniko? | Daemonless, no privileged container, IRSA-native ECR |
| Controller→EKS auth? | Static SA bearer token (Fabric8 limitation) |
| Pod→AWS auth? | IRSA, OIDC token, AssumeRoleWithWebIdentity |
| Why `_` after @Library? | Annotation needs a target |
| Why `defaultContainer 'jnlp'`? | Holds workspace + agent process |
| Why `sleep infinity` in maven/kaniko/aws/tools? | Prevent entrypoint exit so kubectl exec works |
| Why `set -e`? | Abort shell on first failure |
| Why two-pass trivy? | Report first, gate second; no jq for counting |
| Why two image tags? | Immutable SHA + moving branch-latest |
| Why `--ignore-unfixed`? | Only gate on actionable CVEs |
| Why archive JSON reports? | Forensics, re-processing, audit |
| Why `retry(2)` on image scan? | Trivy Java DB / ECR API flakes |
| Where would you add Slack? | Top-level `post` block |
| How fast end-to-end? | ~2 min warm |
| What's missing today? | EBS CSI PVC for caches, cosign signing, NetworkPolicy egress |
