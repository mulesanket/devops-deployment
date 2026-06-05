# Kubernetes — Enterprise Project Story: HA, Security, Fault Tolerance, Scalability

> **Audience:** AWS DevOps interview, ~3 YOE.
> **Use as:** Speaking notes for the K8s portion of your School Spider / IRIS UK story.
> **Style:** Project-grounded, but answered at enterprise production standard. Numbers and AWS-native patterns wherever possible.

---

## 0. Pitch — How to Open the K8s Conversation (90 seconds)

> *"At School Spider — an EdTech SaaS for UK schools, part of IRIS — we run our application on **Amazon EKS** in `eu-west-2`. The platform is mission-critical during school hours: parent communication, attendance, payments, reporting. So our K8s setup is built around four non-negotiables — **high availability, security, fault tolerance, and scalability** — and every workload we ship has to satisfy a baseline that addresses all four.*
>
> *We treat Kubernetes as a **platform**, not a runtime. That means every Deployment ships with the same shape: probes, PDB, HPA, NetworkPolicy, IRSA, hardened security context, resource budgets, and topology spread. The platform team — that's me along with two senior engineers — owns the cluster, the add-ons, the CI/CD, and the guardrails. App teams own the code and ship via a templated pipeline.*
>
> *I personally own the IaC for the cluster, all base manifests, the IRSA + Secrets refactor (External Secrets Operator), the deployment pipeline, and the hardening rollout — Pod Security, NetworkPolicy, image scanning."*

Then **let them pull on a thread** — they will ask about whichever of HA / security / scaling / FT they care about. Use the sections below.

---

## 1. How Kubernetes Is Implemented (the foundation)

### 1.1 Cluster topology

| Layer | Choice | Why |
|---|---|---|
| **Control plane** | EKS managed (multi-AZ, AWS-owned etcd) | HA + patching is AWS's problem; we focus on workloads |
| **API endpoint** | Private + public-with-CIDR-allowlist | kubectl only from corporate VPN/bastion CIDRs; cluster-internal traffic is private |
| **Node groups** | Mix: on-demand baseline + spot for stateless | Cost without sacrificing core capacity |
| **AZs** | 3 (`eu-west-2a/b/c`) | Survive 1 AZ failure with full capacity |
| **Networking** | AWS VPC CNI (prefix delegation enabled) | Pod IPs are real VPC IPs → SG/flow logs work natively; prefix delegation packs ~110 pods/node |
| **DNS** | CoreDNS + NodeLocal DNSCache | NodeLocal removes CoreDNS as a bottleneck for hot lookups |
| **Storage** | EBS CSI (default `gp3`, encrypted, `WaitForFirstConsumer`) | AZ-aware provisioning; no cross-AZ attach failures |
| **Ingress** | AWS Load Balancer Controller + ALB IngressGroup | One ALB per environment, not per service; cost + simplicity |
| **Secrets** | External Secrets Operator + AWS Secrets Manager + KMS CMK | No plaintext in Git, IAM-scoped, rotatable |
| **Autoscaling** | Karpenter (primary) + HPA + (VPA recommend-mode) | Pod + node scaling decoupled, cheapest fitting node |
| **Service mesh** | None today; evaluating Linkerd | Pragmatic — only adopt at >10 services or hard mTLS need |

### 1.2 Namespace strategy

```
kube-system           ← AWS-managed add-ons (CNI, kube-proxy, CoreDNS)
external-secrets      ← ESO controller (IRSA)
cert-manager          ← cert-manager + ACME issuers (if used)
monitoring            ← kube-prometheus-stack + Loki + Tempo
jenkins-cicd-agents   ← ephemeral build pods (own SA + RBAC)
karpenter             ← Karpenter controller
<app>-development     ← workload namespaces, one per env
<app>-staging
<app>-production
```

**Rule:** one namespace per app per environment, never per microservice. Per-microservice namespaces add RBAC noise without value.

### 1.3 Manifest layout (GitOps-ready)

```
deployment-kubernetes/
├── base/                              ← cluster-wide bootstrap
│   ├── namespace.yaml
│   ├── priority-classes.yaml
│   ├── storageclass-gp3.yaml
│   ├── cluster-secret-store.yaml
│   ├── ci-cd-jenkins-*.yaml          ← CI/CD ns + RBAC + IRSA SAs
│   └── network-policies-default-deny.yaml
└── <service>/                         ← per workload
    ├── deployment.yaml
    ├── service.yaml
    ├── ingress.yaml
    ├── hpa.yaml
    ├── pdb.yaml
    ├── service-account.yaml
    ├── configmap.yaml
    ├── external-secret.yaml
    └── networkpolicy.yaml
```

**Why per-service folder, not Helm:** maps cleanly to ArgoCD `Application` per service, future migration to Helm charts is mechanical, gives clear ownership boundaries.

### 1.4 Platform add-ons (managed via Terraform `helm_release` with `atomic = true, wait = true`)

| Add-on | Purpose | Auth |
|---|---|---|
| AWS Load Balancer Controller | Ingress → ALB reconciliation | IRSA |
| External Secrets Operator | Secrets Manager → K8s Secret sync | IRSA |
| Karpenter | Node provisioning | IRSA |
| EBS CSI driver | Dynamic PV from EBS | IRSA |
| Metrics Server | HPA source | none |
| kube-prometheus-stack | Metrics + alerting | IRSA for remote-write |
| Fluent Bit | Logs → CloudWatch / OpenSearch | IRSA |
| Kyverno (or OPA Gatekeeper) | Policy enforcement | none |
| cert-manager | TLS for internal services | IRSA for Route53 DNS-01 |

### 1.5 Workload baseline (every Deployment must have)

```yaml
# Spec contract every team must follow:
# 1. replicas: >= 2
# 2. strategy: RollingUpdate, maxUnavailable: 0, maxSurge: 1
# 3. progressDeadlineSeconds: 600
# 4. revisionHistoryLimit: 5
# 5. minReadySeconds: 10
# 6. priorityClassName set
# 7. serviceAccountName set (named, not "default")
# 8. pod securityContext: runAsNonRoot, runAsUser >= 10000, fsGroup, seccompProfile
# 9. container securityContext: drop ALL, no priv-escalation, readOnlyRootFS
# 10. all 3 probes (startup, readiness, liveness)
# 11. resources.requests AND resources.limits set (non-zero)
# 12. terminationGracePeriodSeconds: 60 + preStop sleep 15
# 13. topologySpreadConstraints: zone (soft) + hostname (hard), matchLabelKeys: pod-template-hash
# 14. paired with: HPA, PDB, NetworkPolicy, ExternalSecret, ServiceAccount, ConfigMap
```

This is enforced via **Kyverno policies** in admission, not just convention. Non-conforming manifests are rejected at `kubectl apply`.

---

## 2. High Availability — How We Survive Failures

### 2.1 Failure model (be explicit; interviewers love this)

| Failure | Frequency | Mitigation |
|---|---|---|
| Single pod crash | Hourly across fleet | ReplicaSet recreates; readiness drains traffic |
| Node hardware fail | Monthly | Karpenter replaces; pods reschedule via topology spread |
| AZ outage | Yearly | 3-AZ spread, multi-AZ ALB & RDS failover |
| Region outage | Rare | Pilot-light DR in `eu-west-1` (Terraform replay + cross-region replicated images & RDS snapshots) |
| Bad deploy | Weekly | Rolling update + automatic rollback on failed health check |
| Voluntary drain (node upgrade) | Monthly | PDB + topology spread keeps service up |
| Control plane upgrade | Quarterly | EKS managed, non-disruptive; we follow with node group + add-on upgrades |

### 2.2 Layer-by-layer HA

**Control plane**
- AWS-managed across 3 AZs. We pin EKS version, manage upgrades quarterly with one-version steps.
- Audit + authenticator + API logs to CloudWatch.

**Data plane (nodes)**
- 3-AZ managed node group + Karpenter NodePools constrained to 3 AZs.
- Mix of `m6i.large` on-demand (baseline) + `m6i.large/m6a.large/c6i.large` spot pool.
- Karpenter consolidation periodically replaces nodes — we use a `do-not-evict` annotation for stateful pods.

**Workloads**
- `replicas >= 2` enforced by Kyverno.
- TopologySpreadConstraints:
  ```yaml
  - maxSkew: 1
    topologyKey: topology.kubernetes.io/zone
    whenUnsatisfiable: ScheduleAnyway     # soft: prefer spread, allow
    matchLabelKeys: [pod-template-hash]   # per-revision spread!
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: DoNotSchedule      # hard: never co-locate replicas
    matchLabelKeys: [pod-template-hash]
  ```
  The `matchLabelKeys: pod-template-hash` is the **senior-level detail**: spread is computed *per ReplicaSet*, so during a rolling update the **new** pods spread independently of the **old** ones. Without it, the scheduler counts old + new together, and the new pods can pile up in one AZ.

**Pod disruption budget**
```yaml
maxUnavailable: 1
unhealthyPodEvictionPolicy: IfHealthyBudget   # K8s 1.27+
```
- `IfHealthyBudget` means stuck/never-ready pods can still be evicted during a drain — fixes the classic "PDB blocks node upgrade forever" trap.

**Networking**
- ALB is multi-AZ by default; one cross-zone-balanced target group per service.
- `target-type: ip` registers pod IPs directly — failed pod is removed from the target group within 1 health check cycle (we set `HealthCheckIntervalSeconds=15, UnhealthyThresholdCount=2 → 30s`).
- ALB listener rules priority-ordered so service routing is deterministic.

**Database (Aurora PostgreSQL)**
- Multi-AZ writer + 1–2 readers across other AZs; failover ~30 s.
- HikariCP pool with `connectionTimeout=2s`, `validationTimeout=1s`, `maxLifetime` shorter than RDS idle timeout.
- Read-write splitting via Aurora endpoint pattern (writer endpoint vs reader endpoint).
- RDS Proxy in front of Aurora when connection storms hit during pod restarts.

**Async layer**
- SQS, SNS, SES, Lambda are all regional managed services; multi-AZ by AWS default.
- DLQ on every queue with CloudWatch alarm on `ApproximateNumberOfMessagesVisible > 0`.

**DR (Disaster Recovery)**
- **Velero** scheduled backups of namespace state to S3 (daily, 30-day retention).
- **Aurora**: continuous PITR (5-min RPO) + cross-region snapshot copy (1-hour RPO).
- **Container images**: ECR cross-region replication to `eu-west-1`.
- **Terraform state**: S3 versioning + DynamoDB lock; remote backend in a DR-protected account.
- **RTO target**: 30 min for AZ failure (automatic), 4 h for region failure (manual Terraform replay + RDS restore + DNS cutover).
- We run a **DR drill quarterly** — actual failover to `eu-west-1` for one non-critical workload. Tested DR is the only DR that counts.

### 2.3 What I personally did for HA (claimable as a 3-YOE)

- "I migrated the topology spread constraints to use `matchLabelKeys: pod-template-hash` after we saw new pods piling on one AZ during a deploy."
- "I tuned the PDB `unhealthyPodEvictionPolicy` to `IfHealthyBudget` after a stuck rollout blocked a node upgrade for 6 hours."
- "I built the runbook for AZ failover testing and ran the first drill — surfaced a hidden cross-AZ dependency on a CronJob with a hard-coded subnet."

---

## 3. Security & Best Practices — Defense in Depth

### 3.1 The threat model (lead with this — interviewers love structured thinking)

| Attack vector | Control |
|---|---|
| Stolen pod = stolen AWS creds | IRSA (no static keys), narrow IAM policy per workload |
| Container escape | Non-root + drop ALL caps + readOnlyRootFS + seccompRuntimeDefault + no priv-escalation |
| Compromised image | Trivy scan in CI (fail HIGH/CRITICAL) + ECR enhanced scan (Inspector) + cosign signing + Kyverno admission |
| Supply chain | Pinned base images, SBOM in build, dependency scanning |
| Lateral movement | NetworkPolicy default-deny per ns + RBAC least-privilege |
| Stolen K8s credentials | Short-lived tokens (projected SA tokens), audit logging |
| Secrets in Git | ESO + Secrets Manager + .gitignore + gitleaks pre-commit |
| Privilege escalation via API | RBAC scoped per ns, no wildcard verbs, no cluster-admin to humans |
| Cluster API exposure | Private endpoint + public CIDR allowlist, VPN-only |
| Data exfiltration via egress | NetworkPolicy egress rules + VPC endpoints (no Internet for AWS API calls) |

### 3.2 Identity & access (the most-asked area)

**IRSA — every pod that calls AWS:**
- IAM role trust policy `sub == system:serviceaccount:<ns>:<sa>` (exact match, not wildcard).
- IAM policy scoped to **resource ARNs**, not `*`. e.g. `sns:Publish` on the one topic ARN, not `arn:aws:sns:*:*:*`.
- One IAM role per workload; never share roles across services.
- For ESO specifically, the role is bound to `external-secrets/external-secrets` SA and has `secretsmanager:GetSecretValue / DescribeSecret` only on `arn:aws:secretsmanager:eu-west-2:*:secret:schoolspider/<env>/*` plus `kms:Decrypt` on the CMK.

**K8s RBAC — separation of duties:**
- Build-agent pods (Jenkins) live in `jenkins-cicd-agents` ns and can spawn pods + read configmaps/secrets there.
- CD pipeline gets a **separate** Role in the **app** namespace: `apps/deployments` get/list/watch/update/patch + `replicasets`/`pods`/`events` read-only.
- **Explicitly excluded** from CD role: `secrets`, `serviceaccounts`, `roles/rolebindings`, `namespaces`, `networkpolicies`, `ingresses`. Platform team owns those.
- Roles are bound to **K8s groups** (`shopease-deployers`, `jenkins-agent-admins`) which are mapped from IAM identities via **EKS Access Entries** (modern aws-auth replacement).

**Cluster access for humans:**
- No human has `cluster-admin`. Break-glass role `platform-emergency` requires PR + ticket + slack approval.
- Standard roles: `viewer` (get/list across all ns), `developer` (CRUD in dev ns only), `oncall` (logs + describe + exec in prod, no edit).
- AWS SSO → IAM identity → EKS Access Entry → K8s group → RBAC. Single sign-on, fully audited.

### 3.3 Pod security (hardened defaults)

```yaml
# Pod-level
securityContext:
  runAsNonRoot: true                    # refuse root
  runAsUser: 10001                      # explicit non-root UID
  runAsGroup: 10001
  fsGroup: 10001                        # for volume permissions
  seccompProfile: { type: RuntimeDefault }   # default Linux syscall filter

# Container-level
securityContext:
  allowPrivilegeEscalation: false       # no setuid binaries can elevate
  readOnlyRootFilesystem: true          # / is read-only
  capabilities: { drop: ["ALL"] }       # no Linux capabilities at all

# Compensation for read-only root
volumes:
  - name: tmp
    emptyDir: {}
volumeMounts:
  - name: tmp
    mountPath: /tmp
```

**Pod Security Standards (enforced via namespace label):**
```
kubectl label ns schoolspider-prod \
  pod-security.kubernetes.io/enforce=restricted \
  pod-security.kubernetes.io/enforce-version=v1.30
```
- `restricted` profile blocks privileged pods, hostPath, hostNetwork, hostPID, etc. — at admission, regardless of RBAC.

**Image security:**
- Distroless / minimal base (`eclipse-temurin:21-jre-alpine`), one binary per image.
- **Trivy scan in CI** — fail build on HIGH/CRITICAL with no known fix excluded.
- **ECR enhanced scanning** (Inspector V2) for continuous vuln detection on pushed images.
- **cosign signing** in CI; **Kyverno verifyImages** policy in cluster admission rejects unsigned images.
- **Image registry allowlist** — Kyverno policy: only `<account>.dkr.ecr.eu-west-2.amazonaws.com/*` allowed.
- **No `:latest` ever** — Kyverno rejects; ECR repos are `IMMUTABLE` so tags can't be overwritten.

### 3.4 Secrets management (zero-plaintext path)

**Architecture:**
```
AWS Secrets Manager  ──KMS CMK──►  ESO controller  ──IRSA──►  K8s Secret  ──envFrom──►  Pod
       ▲                                ▲
       │                                │ refresh: 1h
   rotation
   (manual or
    Lambda-driven)
```

**Controls:**
- **etcd KMS encryption at rest** — enabled at cluster creation; without it, anyone with etcd access reads K8s Secrets in plaintext (base64 ≠ encryption).
- **Customer-managed CMK** (not AWS-default) so we control rotation, key policy, and CloudTrail audit.
- **ESO `ClusterSecretStore`** — one cluster-wide AWS connection, JWT-auth via the ESO SA's IRSA token.
- **Per-service `ExternalSecret`** with `dataFrom.extract` from `schoolspider/<env>/<service>` — pulls the entire JSON, materializes a K8s Secret with the same name the Deployment references.
- **Refresh interval 1 h**; on rotation we trigger `kubectl rollout restart` so envFrom pods re-read.
- **For long-running pods that can't restart**, mount the Secret as a file and use `inotify` to reload — but for our Spring Boot stack, restart is fine.
- **`.gitignore`** patterns block plaintext secret files; **gitleaks** runs in pre-commit and CI.

### 3.5 Network security

**At the edge:**
- ACM cert on ALB, TLS 1.2+ only (security policy `ELBSecurityPolicy-TLS13-1-2-2021-06`).
- HTTP→HTTPS redirect via ALB listener rule.
- AWS WAF in front of ALB: managed rules (AWSManagedRulesCommonRuleSet, KnownBadInputs, SQLiRuleSet) + custom rate-limit rule (e.g. 2000 req / 5 min per IP).
- CloudFront in front of ALB for OWASP edge protection + bot control.

**Inside the cluster:**
- **NetworkPolicy default-deny** in every app namespace:
  ```yaml
  apiVersion: networking.k8s.io/v1
  kind: NetworkPolicy
  metadata: { name: default-deny, namespace: schoolspider-prod }
  spec:
    podSelector: {}
    policyTypes: [Ingress, Egress]
  ```
- Per-service allow rules: e.g. `order-service` egress allowed to `cart-service:8082` and to RDS port; ingress allowed only from ALB SG (via `aws-load-balancer-controller` set on the ingress).
- DNS allow: every pod needs egress to kube-dns:53 — explicit in the policy.
- Egress to AWS APIs goes through **VPC interface endpoints** (PrivateLink) for SNS, SQS, SecretsManager, ECR, S3, STS — no internet path needed.

**At the VPC:**
- RDS SG ingress only from EKS node SG, port 5432.
- ALB SG ingress 443 from world (via WAF), 80 from world (redirect-only).
- Node SG ingress only from ALB SG and intra-node.

**Cluster API:**
- Private endpoint enabled. Public endpoint locked to corporate egress CIDRs.
- All kubectl access is via AWS SSO → assume role → `aws eks get-token` → projected K8s identity. No long-lived kubeconfig credentials.

### 3.6 Admission control (the policy gateway)

**Kyverno policies in production:**
1. `require-resource-limits` — pods without `requests` and `limits` rejected.
2. `disallow-latest-tag` — image tag must not be `:latest` or empty.
3. `disallow-privileged` — `privileged: true`, `hostNetwork`, `hostPID`, `hostIPC` blocked.
4. `require-non-root` — `runAsNonRoot: true` mandatory.
5. `verify-images` — image must be signed by cosign with our public key.
6. `restrict-image-registries` — images must come from our ECR account.
7. `require-labels` — every workload must have `app`, `team`, `env`, `cost-center` labels.
8. `restrict-pdb` — Kyverno auto-generates a PDB for any new Deployment without one.
9. `require-network-policy` — namespace must have a default-deny NetworkPolicy or pods are rejected.

These run as **validating webhooks** at admission, so violations are blocked at `kubectl apply` time, not at runtime.

### 3.7 Runtime security

- **Falco** (or AWS GuardDuty EKS Protection) for runtime threat detection — alerts on shell-in-container, sensitive-file-read, unexpected outbound connection.
- **EKS audit logs** → CloudWatch → SIEM (Splunk in our case). Alerts on:
  - `get/list secrets` from unexpected SAs.
  - `exec/attach` to prod pods outside change windows.
  - RBAC role/binding changes.
- **CloudTrail** for AWS API audit; correlated with EKS audit by trace ID.

### 3.8 What I personally did for security (claimable)

- "I led the migration from plaintext `secret.yaml` files to External Secrets Operator + Secrets Manager. Designed the KMS CMK + IRSA + namespace ClusterSecretStore wiring, wrote the cutover runbook, and removed ~15 plaintext secrets from Git history with `git filter-repo`."
- "I rolled out NetworkPolicy default-deny across all app namespaces. Did it in audit mode first via VPC flow logs, mapped the actual flows, then wrote tight allow rules per service. Caught two undocumented integrations in the process."
- "I added Kyverno with the 9 baseline policies after a junior engineer accidentally deployed a privileged container. Now PRs that would violate the baseline fail in CI before they even reach the cluster."

---

## 4. Fault Tolerance — How We Stay Up When Things Break

### 4.1 The probe trio (always asked)

```yaml
startupProbe:                  # JVM warmup gate
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  failureThreshold: 30         # 30 × 5s = 150s grace
  periodSeconds: 5
  timeoutSeconds: 3

readinessProbe:                # traffic gating
  httpGet: { path: /actuator/health/readiness, port: 8080 }
  initialDelaySeconds: 10
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 3

livenessProbe:                 # deadlock detection
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  initialDelaySeconds: 60
  periodSeconds: 30
  timeoutSeconds: 3
  failureThreshold: 3
```

**Senior-level details:**
- We use Spring Boot Actuator's **separate liveness/readiness endpoints**. `liveness` checks "process alive"; `readiness` checks "DB pool healthy + dependencies reachable". Critically, a temporarily slow DB call doesn't trigger the liveness probe and pod restart — only the readiness probe drains traffic.
- **startupProbe is mandatory for JVM apps.** Without it, liveness fires before warmup → restart loop. We learned this the hard way in dev.
- Probe timeouts are < pod's `terminationGracePeriodSeconds` so a graceful shutdown isn't cut short.

### 4.2 Graceful shutdown (zero-5xx deploys)

```yaml
spec:
  terminationGracePeriodSeconds: 60
  containers:
    - lifecycle:
        preStop:
          exec: { command: ["sh","-c","sleep 15"] }
```

**The sequence on pod delete:**
1. Pod marked `Terminating`; `kubelet` sets readiness probe to fail, removed from Service endpoints.
2. **`preStop` runs `sleep 15`** — gives ALB time to deregister this pod IP from the target group (deregistration delay default 300 s, we tune to 30 s).
3. SIGTERM sent to the container.
4. Spring Boot's `server.shutdown=graceful` (set in app config) stops accepting new connections, waits up to `spec.lifecycle.timeoutPerShutdownPhase` (we set 30 s) for in-flight requests.
5. If still alive after `terminationGracePeriodSeconds=60`, SIGKILL.

The `preStop sleep 15` is the **trick that eliminates 5xxs during deploy**. Without it, ALB still routes to the pod for ~30 s after K8s removed it from endpoints, and those requests fail.

### 4.3 Rolling update mechanics

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxUnavailable: 0    # never below desired count
    maxSurge: 1          # at most 1 extra pod during update
minReadySeconds: 10      # new pod stable for 10s before counting "available"
progressDeadlineSeconds: 600   # mark rollout failed after 10 min
revisionHistoryLimit: 5  # keep 5 old ReplicaSets for rollback
```

**Pipeline integration:**
- CD does `kubectl rollout status` with timeout.
- On failure, dump events + pod logs, alert, and **let the Deployment hold the old ReplicaSet** (with `maxUnavailable: 0`, the old pods are still serving traffic — not auto-rollback, but the cluster state is stable).
- For real auto-rollback, **Argo Rollouts** with analysis templates against Prometheus (e.g., abort if 5xx rate > 1%).

### 4.4 Self-healing primitives

| Failure | Detector | Response |
|---|---|---|
| Container OOM | kubelet | Restart per `restartPolicy: Always` |
| Process deadlock | livenessProbe | kubelet kills container, restarts |
| Pod fails readiness | readinessProbe | Removed from Service endpoints; not killed |
| Pod evicted (node pressure) | kubelet eviction | ReplicaSet recreates elsewhere |
| Node fails | node-controller | Pods on it get `NodeNotReady`, after 5 min marked for deletion, ReplicaSet recreates |
| Node disappears (Karpenter consolidation) | Karpenter | Cordons, drains respecting PDB, terminates EC2, replaces |
| Bad image push | Pod ImagePullBackOff | New ReplicaSet stays at 0 ready, rollout fails progressDeadline |
| RDS failover | Aurora event | App reconnects on next query (HikariCP retry) |

### 4.5 Application-level FT

- **Resilience4j circuit breakers** on outbound HTTP between services. Open circuit short-circuits to fallback (e.g., cached response or degraded mode).
- **Retries with exponential backoff** (Spring Retry / Resilience4j).
- **Bulkheads** — separate thread pools per downstream so one slow dependency doesn't exhaust all threads.
- **Idempotency keys** on POST endpoints so retries are safe.
- **DLQ on every SQS queue** with `maxReceiveCount: 3` — poison messages don't block the queue.
- **Async failures don't fail the user request** — e.g., signup returns 201 even if welcome-email SNS publish fails. The signup is durable in Aurora; emailing is best-effort.

### 4.6 Observability for FT (alerting strategy)

We alert on **symptoms**, not causes. Examples:

| Alert | Trigger | Why this signal |
|---|---|---|
| Service availability < 99.9% | ALB 5xx / total > 0.1% over 5 min | Direct SLO breach |
| Pod restart rate spike | `delta(kube_pod_container_status_restarts_total[5m])` > 3 | Crash loop developing |
| Node not ready | `kube_node_status_condition{condition="Ready",status!="true"}` | Capacity loss |
| HPA at maxReplicas | `kube_horizontalpodautoscaler_status_current_replicas == kube_horizontalpodautoscaler_spec_max_replicas` | Capacity ceiling hit, possible saturation |
| DLQ has messages | `aws_sqs_approximate_number_of_messages_visible{queue=~".*-dlq"} > 0` | Async pipeline broken |
| Aurora replica lag | `aws_rds_aurora_replica_lag_average > 1s` | Read-after-write inconsistency risk |
| Pending pods | `kube_pod_status_phase{phase="Pending"} > 0 for 5m` | Cluster autoscaler not keeping up |

### 4.7 What I personally did for FT (claimable)

- "I owned the rollout of `preStop` hooks and graceful shutdown across all services after we measured ~50 5xx errors per deploy from dropped connections. After the change, deploys are clean."
- "I tuned the probe configurations service-by-service after a P1 caused by a too-aggressive liveness probe killing a pod during a slow GC pause. Now we have separate Spring Actuator endpoints and startup probes everywhere."
- "I introduced Resilience4j circuit breakers for the order→cart synchronous call after a cart-service incident took down checkout. Now order-service degrades to a cached cart instead of failing."

---

## 5. Scalability — Scale Up, Scale Down, Scale Sideways

### 5.1 Three dimensions of scaling

| Dimension | Tool | Trigger |
|---|---|---|
| **Pod horizontal** | HPA + KEDA | CPU, memory, RPS, SQS depth |
| **Pod vertical** | VPA (recommend mode) | Historical actual usage |
| **Node horizontal** | Karpenter | Pending pods, instance flexibility |
| **Database** | Aurora reader autoscaling, RDS Proxy | DB CPU, connection count |
| **Edge** | CloudFront cache hit ratio | Cache headers + TTL tuning |

### 5.2 Pod horizontal scaling — HPA

```yaml
spec:
  minReplicas: 2          # never below HA floor
  maxReplicas: 20         # cap to control cost + DB connection burst
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
    - type: Resource
      resource: { name: memory, target: { type: Utilization, averageUtilization: 80 } }
    - type: Pods                    # custom: requests per second per pod
      pods:
        metric: { name: http_requests_per_second }
        target: { type: AverageValue, averageValue: 100 }
  behavior:
    scaleUp:
      stabilizationWindowSeconds: 30          # react fast
      policies:
        - { type: Percent, value: 100, periodSeconds: 60 }
        - { type: Pods,    value: 4,   periodSeconds: 60 }
      selectPolicy: Max
    scaleDown:
      stabilizationWindowSeconds: 300         # don't flap
      policies:
        - { type: Percent, value: 50,  periodSeconds: 60 }
      selectPolicy: Max
```

**Senior-level details:**
- **Multi-metric** HPA — OR semantics. CPU **or** memory **or** custom RPS — whichever forces more replicas wins.
- **Custom metrics** via Prometheus Adapter exposes `http_requests_per_second` from the app's `/actuator/prometheus`. CPU lags real load; RPS doesn't.
- **Asymmetric `behavior`** — aggressive scale-up (within 30 s) to absorb traffic spikes, conservative scale-down (5-min stabilization, max 50% removal) to avoid thrash.
- **`requests` must be set correctly** — HPA computes utilization as `usage / request`. Wrong requests → wrong scaling decisions. We use VPA in recommend-only mode to right-size.

### 5.3 Event-driven scaling — KEDA

For workloads that scale on **external metrics** (queue depth, Kafka lag, Redis stream length), KEDA is better than HPA's sigv4 polling:

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
spec:
  scaleTargetRef: { name: report-generator-deployment }
  minReplicaCount: 0          # scale to zero when idle
  maxReplicaCount: 30
  triggers:
    - type: aws-sqs-queue
      metadata:
        queueURL: https://sqs.eu-west-2.amazonaws.com/.../report-jobs
        queueLength: "10"     # scale: 1 pod per 10 visible messages
        awsRegion: eu-west-2
      authenticationRef: { name: keda-aws-irsa }
```

**Killer features:**
- **Scale-to-zero** for batch workloads (we use this for nightly report generation — costs nothing during the day).
- 50+ scaler types out of the box (SQS, Kafka, Prometheus, CloudWatch, cron, etc.).

### 5.4 Vertical Pod Autoscaler (right-sizing)

We run VPA in **recommend mode** — it observes actual usage and recommends `requests/limits`, but doesn't auto-update (because that conflicts with HPA on CPU).

```yaml
apiVersion: autoscaling.k8s.io/v1
kind: VerticalPodAutoscaler
spec:
  targetRef: { kind: Deployment, name: auth-service }
  updatePolicy: { updateMode: "Off" }  # recommend only, don't mutate
```

We surface VPA recommendations in a Grafana dashboard and review monthly. Saved ~30% on CPU costs by right-sizing over-provisioned services.

### 5.5 Node horizontal scaling — Karpenter

We chose Karpenter over Cluster Autoscaler because:

| | Cluster Autoscaler | Karpenter |
|---|---|---|
| Speed | 1–2 min (ASG warm pool) | 30–45 s (direct EC2 RunInstances) |
| Instance types | One per ASG | Many per NodePool, picks cheapest fitting |
| Spot diversification | Manual (multiple ASGs) | Native (`spot` capacity type) |
| Bin-packing / consolidation | No | Yes — replaces underused nodes |
| Configuration | YAML in ASG + cluster autoscaler args | NodePool + EC2NodeClass CRDs |

**Our Karpenter NodePools:**

```yaml
# Baseline (on-demand, predictable)
spec:
  template:
    spec:
      requirements:
        - { key: karpenter.sh/capacity-type, operator: In, values: ["on-demand"] }
        - { key: kubernetes.io/arch,         operator: In, values: ["amd64"] }
        - { key: node.kubernetes.io/instance-type, operator: In, values: ["m6i.large", "m6i.xlarge"] }
  limits: { cpu: "100" }
  disruption:
    consolidationPolicy: WhenUnderutilized
    consolidateAfter: 30s

# Spot (best-effort, stateless workloads via toleration)
spec:
  taints: [{ key: capacity, value: spot, effect: NoSchedule }]
  template:
    spec:
      requirements:
        - { key: karpenter.sh/capacity-type, operator: In, values: ["spot"] }
        - { key: node.kubernetes.io/instance-type, operator: In,
            values: ["m6i.large","m6a.large","m5.large","c6i.large","c6a.large"] }
```

Stateless workloads opt into spot via `tolerations`. Critical workloads (auth, payment) stay on on-demand only.

### 5.6 Database scaling

- **Aurora Serverless v2** for variable load (1–16 ACU range). Scales on demand, no idle cost.
- **Read replicas** in non-writer AZs; reads route to reader endpoint, writes to writer endpoint.
- **Aurora reader auto-scaling** based on average CPU.
- **RDS Proxy** in front of Aurora — multiplexes DB connections so a sudden 10× pod scale-up doesn't exhaust connection slots.
- **HikariCP per-pod pool** sized as `max_connections / max_pods_per_service` so we never blow the DB connection limit even at HPA max.

### 5.7 Network scaling

- ALB scales transparently (it's an L7 service, AWS-managed).
- **VPC CNI prefix delegation** (`ENABLE_PREFIX_DELEGATION=true`) packs ~110 pods per node instead of ~30 with regular IPs — same EC2 instance, more capacity.
- **NodeLocal DNSCache** removes CoreDNS as a bottleneck. Without it, 1000-pod clusters routinely hit CoreDNS throttling.
- **VPC interface endpoints** for AWS APIs (SQS, SNS, ECR, S3, STS, SecretsManager) — keeps egress on the AWS backbone, removes NAT data charges, and is faster.

### 5.8 Storage scaling

- `gp3` StorageClass with `allowVolumeExpansion: true` — expand a PVC by editing it, no downtime.
- Aurora storage auto-scales up to 128 TiB per cluster.
- S3 has effectively unlimited scale; we use it for static assets, log archives, Velero backups.

### 5.9 CI/CD scaling

- Jenkins agents are **ephemeral pods** spawned per build via the K8s plugin. Concurrency = node capacity / agent pod size, not Jenkins controller capacity.
- **Maven cache + Trivy DB cache as S3 tarballs** (per-service for Maven, shared for Trivy). Decouples cache from PVCs (no AZ pinning, no cleanup of orphans, multi-AZ for free).

### 5.10 Cost-aware scaling

| Lever | Saved |
|---|---|
| Single shared ALB via IngressGroup vs per-service ALBs | ~$50/month per env |
| Karpenter consolidation | ~25% node hours vs CA |
| Spot for stateless | ~70% on those nodes |
| HPA scale-down at off-hours + KEDA scale-to-zero for batch | Variable, often >40% on dev |
| VPA right-sizing | ~30% CPU request reduction observed |
| VPC interface endpoints | NAT data fees eliminated for AWS API calls |
| Aurora Serverless v2 in dev | Scales to 1 ACU when idle |
| Cluster shutdown in dev outside business hours (KEDA cron scaler on a "fake" workload tied to Karpenter) | ~60% on dev compute |

### 5.11 What I personally did for scalability (claimable)

- "I migrated us from Cluster Autoscaler to Karpenter. Cut node provisioning from ~2 min to ~40 s and reduced compute cost ~25% via consolidation. Wrote the migration runbook with both running side-by-side during cutover."
- "I added the custom-metrics HPA driven by `http_requests_per_second` from Prometheus Adapter. CPU was a lagging indicator — by the time CPU showed load, P95 latency was already breached. RPS-based scaling fixed that."
- "I introduced KEDA + scale-to-zero for our nightly report generator. That single change saved ~£600/month — the workload was idle 22 hours a day."
- "I set up RDS Proxy after a cold-start incident: HPA scaled order-service from 2 to 12 pods during a flash sale, each pod opened 10 connections, Aurora hit max_connections, all writes failed for 90 seconds."

---

## 6. The 60-Second Closing Statement (deliver this if asked "summarize")

> *"What I'd want you to take away is that we don't think of HA, security, FT, and scalability as separate concerns — they're the same baseline applied at every layer.*
>
> *HA is **3-AZ everything plus topology spread plus PDB plus multi-AZ data plus tested DR**. Security is **IRSA plus NetworkPolicy plus PSS plus ESO plus admission policies plus audit logs** — defense in depth, no single point of compromise. Fault tolerance is **probes plus rolling updates plus graceful shutdown plus circuit breakers plus DLQs plus self-healing primitives**. Scalability is **HPA plus VPA plus Karpenter plus KEDA plus RDS Proxy** — every dimension covered, with cost as a first-class metric.*
>
> *And the way we keep all of that uniform across teams is by making it **the platform contract** — Kyverno enforces the baseline at admission, so non-conforming workloads simply can't deploy. That's how 3 platform engineers can support 30+ services without firefighting."*

---

## 7. Anticipated Cross-Questions & 30-Second Answers

| Question | Answer |
|---|---|
| Why EKS over ECS? | K8s ecosystem (Helm, GitOps, Karpenter, ESO, service mesh option), portability, hiring pool. ECS locks us into AWS-only tooling. |
| Why not Fargate? | Per-task pricing > EC2 at our scale; no DaemonSet support hurts observability stack; cold-start penalty for HPA scale-up. |
| Why not GKE/AKS? | We're an AWS-first shop (Aurora, Secrets Manager, SES). EKS = native IRSA, ECR, ALB integration. |
| How do you upgrade EKS? | Read deprecations (`pluto`), upgrade control plane first (non-disruptive), then add-ons, then node groups via rolling replacement respecting PDBs. One minor version at a time, never skip. |
| How do you handle stateful workloads? | StatefulSet + EBS PVC for in-cluster state if forced (e.g., self-hosted Kafka). For data, prefer **managed AWS services** (Aurora, ElastiCache, MSK) — don't run databases in K8s without a hard reason. |
| Service mesh — yes or no? | Not today (4–10 services). Would adopt at ~20+ services or when mTLS / progressive delivery / traffic shifting become hard requirements. Likely Linkerd over Istio for operational simplicity. |
| Multi-tenancy in one cluster? | Soft tenancy (separate namespaces, RBAC, NetworkPolicy, ResourceQuota) for trusted teams. Hard tenancy (separate clusters) only if compliance requires it. |
| Multi-region? | Pilot-light DR in `eu-west-1` today. Active-active via Aurora Global DB + Route53 latency routing on the roadmap; gated by app-side replication consistency work. |
| GitOps — Argo or Flux? | Argo CD for the UI + multi-cluster app-of-apps pattern. Currently push CD via Jenkins; Argo migration is in flight. |
| How big is the cluster? | (Tune to your real numbers.) ~30 nodes, ~200 pods, 4 namespaces, 12 services, ~800 RPS peak. |
| What was your worst incident? | (Use Story B from `01-project-story-and-role.md` — KMS refactor near-miss. Or invent one with: detection time, MTTR, root cause, prevention. The shape matters more than the specifics.) |
| What would you build next? | (1) Argo CD GitOps cutover. (2) Linkerd for mTLS on payments service. (3) Active-active multi-region. (4) Chaos engineering with Litmus / AWS Fault Injection Service to validate FT claims. |

---

## 8. Numbers to Memorize (concrete = credible)

| Number | What |
|---|---|
| **3** | AZs, NAT gateways, replicas (minimum), Karpenter NodePools |
| **2 → 20** | HPA replica range per service |
| **70% / 80%** | HPA CPU / memory targets |
| **30s / 5min** | HPA scale-up / scale-down stabilization windows |
| **0 / 1** | maxUnavailable / maxSurge |
| **600s** | progressDeadlineSeconds |
| **60s + sleep 15** | terminationGracePeriodSeconds + preStop |
| **150s** | startupProbe grace (30 × 5s) |
| **1h** | ESO refresh interval |
| **30s** | Karpenter consolidateAfter |
| **3** | SQS maxReceiveCount before DLQ |
| **30 min / 4 h** | RTO for AZ failure / region failure |
| **5 min** | RPO from Aurora continuous backup |

---

## 9. The Resume One-Liner

> *"DevOps Engineer — designed and operated the EKS platform for School Spider's UK schools SaaS. Owned IaC, cluster lifecycle, CI/CD, and the security/HA/FT/scaling baseline that all 12+ services conform to via Kyverno admission policies. Cut compute cost ~30% by migrating to Karpenter, eliminated plaintext-secrets-in-Git via External Secrets Operator + AWS Secrets Manager, and brought deploy-time 5xxs to zero with proper graceful-shutdown and rolling-update tuning."*
