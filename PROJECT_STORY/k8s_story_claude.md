# Kubernetes Interview Story for AWS DevOps Role
## School Spider / IRIS UK — Production-Grade Walkthrough

> **Audience:** AWS DevOps interview, ~3 YOE.
> **Use this doc as:** Speaking notes for the K8s portion of the interview.
> **Format:** Read aloud, not copy-paste. Numbers are concrete, every pattern is enterprise-grade.

---

# 0. The 90-Second Opening Pitch

When the interviewer says *"Tell me about your Kubernetes setup,"* lead with this:

> *"At School Spider — an EdTech SaaS used by UK schools and part of IRIS — we run our application on **Amazon EKS** in `eu-west-2` across three AZs. The platform is mission-critical during school hours: parent communication, attendance, payments, reporting. So our K8s setup is built around four non-negotiables — **high availability, security, fault tolerance, and scalability** — and every workload that ships has to meet a baseline that addresses all four.*
>
> *We treat Kubernetes as a **platform**, not just a runtime. That means every Deployment ships with the same shape: probes, PDB, HPA, NetworkPolicy, IRSA-scoped ServiceAccount, hardened SecurityContext, resource budgets, and topology spread. The platform team owns the cluster, the add-ons, the CI/CD, and the guardrails. App teams own the code and ship via a templated pipeline.*
>
> *I personally owned the IaC for the cluster, the base manifests, the IRSA + Secrets refactor (External Secrets Operator + AWS Secrets Manager), the deployment pipeline, and the hardening rollout — Pod Security, NetworkPolicy default-deny, image scanning, and the Kyverno admission policies that enforce the baseline."*

Then **let them pull on a thread** — they'll pick whichever of HA / security / scaling / FT they care about. Use the sections below.

---

# 1. Project Context

School Spider is a multi-service EdTech platform. UK schools rely on it during school hours, so:

- **Working hours = peak load** (mornings 7–9 AM and 3–5 PM are spiky).
- **Off-hours = minimal load** (good fit for scale-down).
- **Term boundaries = traffic floods** (start of term, parent's evening events).
- **Compliance**: UK GDPR + DfE data handling — secrets and audit trails are not optional.

The application is broken into multiple backend microservices (auth, parent communications, attendance, billing, reporting, etc.) plus a frontend SPA. Each service runs on Kubernetes with its own:

```
Deployment, Service, Ingress, ConfigMap, ExternalSecret,
ServiceAccount (IRSA-annotated where needed),
HPA, PDB, NetworkPolicy, PriorityClass,
SecurityContext (pod-level + container-level),
Resource requests/limits, Health probes (startup/readiness/liveness)
```

The goal: deployments that are safe, traffic that's routed correctly, services that scale on demand, and failures that recover automatically — all enforced at the platform layer so app teams can't accidentally ship something unsafe.

---

# 2. How Kubernetes Was Implemented (the foundation)

## 2.1 Cluster topology

| Layer | Choice | Why |
|---|---|---|
| **Control plane** | EKS managed (multi-AZ, AWS-owned etcd) | HA + patching is AWS's problem; we focus on workloads |
| **API endpoint** | Private + public-with-CIDR-allowlist | kubectl only from corporate VPN/bastion CIDRs |
| **Node groups** | Managed group (on-demand baseline) + Karpenter (spot for stateless) | Cost without sacrificing core capacity |
| **AZs** | 3 (`eu-west-2a/b/c`) | Survive 1 AZ failure with full capacity |
| **Networking** | AWS VPC CNI with **prefix delegation** | Pod IPs are real VPC IPs (SG/flow-logs work natively); ~110 pods/node instead of ~30 |
| **DNS** | CoreDNS + **NodeLocal DNSCache** | NodeLocal removes CoreDNS as a bottleneck for hot lookups |
| **Storage** | EBS CSI (`gp3`, encrypted, `WaitForFirstConsumer`) | AZ-aware provisioning; no cross-AZ attach failures |
| **Ingress** | AWS Load Balancer Controller + ALB **IngressGroup** | One ALB per environment, not per service; saves $48+/month |
| **Secrets** | External Secrets Operator + AWS Secrets Manager + KMS CMK | No plaintext in Git, IAM-scoped, rotatable |
| **Autoscaling** | Karpenter (nodes) + HPA (pods) + KEDA (event-driven) + VPA (recommend mode) | Pod + node scaling decoupled, cheapest fitting node |
| **Policy** | Kyverno (admission control) | Baseline enforced at admission, not by review |
| **Observability** | kube-prometheus-stack + Loki + CloudWatch | Metrics, logs, alerts |
| **Service mesh** | None today; evaluating Linkerd | Pragmatic — only adopt at >10–15 services |

## 2.2 Namespace strategy

```
kube-system           ← AWS-managed add-ons (CNI, kube-proxy, CoreDNS)
external-secrets      ← ESO controller (IRSA)
karpenter             ← Karpenter controller (IRSA)
monitoring            ← kube-prometheus-stack + Loki
jenkins-cicd-agents   ← ephemeral build pods (own SA + RBAC)
kyverno               ← Kyverno admission controller
schoolspider-dev      ← workload namespaces, one per env
schoolspider-staging
schoolspider-prod
```

**Rule we follow:** *one namespace per app per environment, never per microservice.* Per-microservice namespaces add RBAC noise without value.

## 2.3 Manifest layout (GitOps-ready)

```
deployment-kubernetes/
├── base/                              ← cluster-wide bootstrap
│   ├── namespace.yaml
│   ├── priority-classes.yaml
│   ├── storageclass-gp3.yaml
│   ├── cluster-secret-store.yaml
│   ├── network-policies-default-deny.yaml
│   └── ci-cd-jenkins-*.yaml
└── <service>/                         ← per workload
    ├── deployment.yaml
    ├── service.yaml
    ├── ingress.yaml
    ├── hpa.yaml
    ├── pdb.yaml
    ├── networkpolicy.yaml
    ├── service-account.yaml
    ├── configmap.yaml
    └── external-secret.yaml
```

This layout maps cleanly to **Argo CD `Application` per service** when we move to GitOps.

## 2.4 The workload baseline contract (enforced via Kyverno)

Every Deployment must satisfy this contract. Non-conforming manifests are **rejected at admission**, not at PR review:

```
1.  replicas: >= 2
2.  strategy: RollingUpdate, maxUnavailable: 0, maxSurge: 1
3.  progressDeadlineSeconds: 600
4.  revisionHistoryLimit: 5
5.  minReadySeconds: 10
6.  priorityClassName set
7.  serviceAccountName set (named, not "default")
8.  pod securityContext: runAsNonRoot, UID >= 10000, fsGroup, seccompProfile
9.  container securityContext: drop ALL caps, no priv-escalation, readOnlyRootFS
10. all 3 probes (startup, readiness, liveness) hitting separate Spring Actuator endpoints
11. resources.requests AND resources.limits set (non-zero)
12. terminationGracePeriodSeconds: 60 + preStop sleep 15
13. topologySpreadConstraints: zone (soft) + hostname (hard), with matchLabelKeys: pod-template-hash
14. paired with: HPA, PDB, NetworkPolicy, ExternalSecret, SA, ConfigMap
```

This is **the contract** — both for interviews and for real platform engineering. If you mention this baseline up front, every subsequent question maps back to it.

---

# 3. Kubernetes Objects Used (with senior-level detail)

## 3.1 Deployment

Deployment was used to manage application Pods.

It handled:
- Replica management
- Rolling updates (with `maxUnavailable: 0, maxSurge: 1`)
- Rollbacks (`revisionHistoryLimit: 5`)
- Self-healing
- Pod template version-tracking via the `pod-template-hash` label

**Production Deployment skeleton (the full senior version):**

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: parent-comms-deployment
  namespace: schoolspider-prod
spec:
  replicas: 3
  revisionHistoryLimit: 5            # keep 5 old ReplicaSets for rollback
  progressDeadlineSeconds: 600       # mark rollout failed after 10 min
  minReadySeconds: 10                # new pod must be Ready 10s before counting "available"

  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0              # never drop below desired count
      maxSurge: 1                    # at most 1 extra pod during update

  selector:
    matchLabels: { app: parent-comms-deployment }   # immutable after creation

  template:
    metadata:
      labels: { app: parent-comms-deployment }
    spec:
      serviceAccountName: parent-comms-sa
      automountServiceAccountToken: true
      priorityClassName: schoolspider-high
      terminationGracePeriodSeconds: 60

      securityContext:                 # pod-level
        runAsNonRoot: true
        runAsUser: 10001
        runAsGroup: 10001
        fsGroup: 10001
        seccompProfile: { type: RuntimeDefault }

      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector: { matchLabels: { app: parent-comms-deployment } }
          matchLabelKeys: [pod-template-hash]   # spread per-revision, not across revisions
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: DoNotSchedule
          labelSelector: { matchLabels: { app: parent-comms-deployment } }
          matchLabelKeys: [pod-template-hash]

      containers:
        - name: app
          image: <ecr>/parent-comms:1.4.2
          imagePullPolicy: IfNotPresent
          ports: [{ containerPort: 8080 }]

          envFrom:
            - configMapRef: { name: parent-comms-config }
            - secretRef:    { name: parent-comms-secret }     # materialized by ESO

          securityContext:               # container-level
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: true
            capabilities: { drop: ["ALL"] }

          lifecycle:
            preStop:
              exec: { command: ["sh","-c","sleep 15"] }   # ALB deregistration race fix

          volumeMounts:
            - { name: tmp, mountPath: /tmp }                # writable scratch since rootFS is RO

          resources:
            requests: { cpu: 250m, memory: 512Mi }
            limits:   { cpu: 500m, memory: 1Gi }

          startupProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            failureThreshold: 30
            periodSeconds: 5
            timeoutSeconds: 3
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: 8080 }
            initialDelaySeconds: 10
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: 8080 }
            initialDelaySeconds: 60
            periodSeconds: 30
            timeoutSeconds: 3
            failureThreshold: 3

      volumes:
        - { name: tmp, emptyDir: {} }
```

**Senior-level details to call out when asked:**

| Field | Why this value |
|---|---|
| `maxUnavailable: 0, maxSurge: 1` | Never below desired replica count during rollout |
| `progressDeadlineSeconds: 600` | Rollout marked failed after 10 min so CD pipeline can react |
| `minReadySeconds: 10` | New pod must stay Ready for 10s before counting as available — protects against flapping |
| `revisionHistoryLimit: 5` | 5 ReplicaSets is enough rollback history without etcd noise |
| `matchLabelKeys: [pod-template-hash]` | **Spread is computed per-ReplicaSet** — during rolling update, new pods spread independently of old ones. Without this, scheduler counts old + new together and new pods can pile up in one AZ. |
| `whenUnsatisfiable: DoNotSchedule` (host) + `ScheduleAnyway` (zone) | Hard host spread (never co-locate replicas), soft zone spread (prefer AZ spread, allow if impossible) |
| `preStop: sleep 15` | ALB deregistration takes ~15–30s; without this hook, ALB still routes to terminating pod → 5xx errors |
| `terminationGracePeriodSeconds: 60` | preStop 15s + Spring Boot graceful shutdown 30s + buffer |
| `automountServiceAccountToken: true` | Required for IRSA to inject the projected token volume |

---

## 3.2 Service

Used to expose Pods internally inside the cluster.

```yaml
apiVersion: v1
kind: Service
metadata:
  name: parent-comms
  namespace: schoolspider-prod
spec:
  type: ClusterIP
  selector: { app: parent-comms-deployment }
  ports:
    - { protocol: TCP, port: 80, targetPort: 8080 }
```

**Critical points to mention:**

- Service `selector` must match Pod labels (silent failure if mismatched).
- With ALB `target-type: ip`, the ALB **bypasses Service ClusterIP** and routes directly to pod IPs. The Service still exists because the AWS LB Controller reads the Service's endpoint list to know which pod IPs to register.
- Empty endpoints = no Ready pods OR label-selector mismatch OR readiness probes failing:
  ```
  kubectl get endpoints parent-comms -n schoolspider-prod
  ```

---

## 3.3 Ingress (with AWS ALB IngressGroup)

We use **one shared ALB across all services** via the `group.name` annotation pattern. This is **the cost-saving feature** of the AWS Load Balancer Controller — without it, each Ingress gets its own ALB ($16+/month each).

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: parent-comms-ingress
  namespace: schoolspider-prod
  annotations:
    # ---- Shared ALB ----
    alb.ingress.kubernetes.io/group.name: schoolspider          # merges 12+ Ingresses into 1 ALB
    alb.ingress.kubernetes.io/group.order: "20"                 # rule priority
    alb.ingress.kubernetes.io/load-balancer-name: schoolspider-alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip                   # routes directly to pod IPs
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTPS": 443}]'
    alb.ingress.kubernetes.io/ssl-redirect: "443"               # force HTTPS
    alb.ingress.kubernetes.io/certificate-arn: arn:aws:acm:eu-west-2:...
    alb.ingress.kubernetes.io/ssl-policy: ELBSecurityPolicy-TLS13-1-2-2021-06
    alb.ingress.kubernetes.io/wafv2-acl-arn: arn:aws:wafv2:...  # WAF in front

    # ---- Per-service health check ----
    alb.ingress.kubernetes.io/healthcheck-path: /api/parent-comms/health
    alb.ingress.kubernetes.io/healthcheck-protocol: HTTP
    alb.ingress.kubernetes.io/success-codes: "200"
    alb.ingress.kubernetes.io/healthcheck-interval-seconds: "15"
    alb.ingress.kubernetes.io/healthy-threshold-count: "2"
    alb.ingress.kubernetes.io/unhealthy-threshold-count: "2"

    # ---- Connection draining ----
    alb.ingress.kubernetes.io/target-group-attributes: deregistration_delay.timeout_seconds=30
spec:
  ingressClassName: alb
  rules:
    - http:
        paths:
          - { path: /api/parent-comms, pathType: Prefix,
              backend: { service: { name: parent-comms, port: { number: 80 } } } }
```

**Senior-level details:**

- **`target-type: ip`** registers pod IPs directly — failed pods are removed within `interval × unhealthy-threshold` = 30s.
- **`deregistration_delay.timeout_seconds=30`** + **`preStop: sleep 15`** together eliminate 5xxs on rollout.
- **WAF in front** for OWASP rules + rate-limiting.
- **TLS 1.3** policy; HTTP→HTTPS redirect at the ALB.

---

## 3.4 ConfigMap & Secret

**ConfigMap** for non-sensitive config:
```yaml
data:
  SPRING_PROFILES_ACTIVE: prod
  SPRING_DATASOURCE_URL: jdbc:postgresql://...
  LOG_LEVEL: INFO
  AWS_REGION: eu-west-2
```

**Secret** is **never** committed as plaintext. We use ExternalSecret + AWS Secrets Manager (see §8).

**Critical line for the interview:**
> *"Kubernetes Secrets are **base64-encoded, not encrypted**. Anyone with `get secrets` RBAC can read them. Real protection comes from etcd KMS encryption + restricted RBAC + audit logging — and ideally not having them in K8s at all if a CSI-mounted alternative is available."*

---

## 3.5 ServiceAccount + IRSA

Each service runs under its own ServiceAccount, IRSA-annotated only when AWS access is needed:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: parent-comms-sa
  namespace: schoolspider-prod
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::<acct>:role/schoolspider-prod-parent-comms-irsa
automountServiceAccountToken: true
```

**The IAM role's trust policy is exact-match on the SA path:**
```json
"Condition": {
  "StringEquals": {
    "<oidc-provider>:sub": "system:serviceaccount:schoolspider-prod:parent-comms-sa"
  }
}
```

**The IAM policy is scoped to resource ARNs, not `*`:**
```json
{
  "Effect": "Allow",
  "Action": ["sns:Publish"],
  "Resource": "arn:aws:sns:eu-west-2:<acct>:schoolspider-prod-parent-events"
}
```

This means **only** that one ServiceAccount in that namespace can publish to that one topic.

---

# 4. How Deployment Was Handled

## 4.1 Image tagging discipline

- **Never `:latest`** (Kyverno blocks it at admission).
- **ECR repos set to `IMMUTABLE`** so a tag can't be silently overwritten.
- **Tag = Git SHA in dev/staging**, **semver in prod** after staging validation.
- **Promotion = retag + push, not rebuild** — the same digest moves through environments.

## 4.2 Rolling update — what actually happens

With `replicas: 3, maxUnavailable: 0, maxSurge: 1`:

```
T=0   [old1, old2, old3]                 ← baseline
T=1   [old1, old2, old3, NEW1-pending]   ← surge pod created
T=2   [old1, old2, old3, NEW1-Ready]     ← waits for readiness probe
T=3   [old1, old2,        NEW1]          ← old3 enters Terminating
                                            → readinessProbe forced fail
                                            → removed from Service endpoints
                                            → preStop: sleep 15s (ALB deregisters)
                                            → SIGTERM → graceful shutdown
T=4   [old1, old2, NEW1, NEW2-pending]
... (repeats until all 3 are NEW)
```

**Why this is zero-downtime:**
1. Surge happens **first** (new pod created before any old pod dies).
2. New pod must pass **readinessProbe** before it's added to Service endpoints.
3. New pod must stay Ready for **`minReadySeconds: 10`** before counting as available.
4. Only then does an old pod start terminating.
5. Old pod's **preStop sleep 15** lets ALB deregister before SIGTERM.
6. Spring Boot's `server.shutdown=graceful` finishes in-flight requests during the 60s grace period.

## 4.3 Rollback

```powershell
kubectl rollout status  deployment/parent-comms-deployment -n schoolspider-prod
kubectl rollout history deployment/parent-comms-deployment -n schoolspider-prod
kubectl rollout undo    deployment/parent-comms-deployment -n schoolspider-prod
kubectl rollout undo    deployment/parent-comms-deployment -n schoolspider-prod --to-revision=4
```

**Interview line:** *"Rollback is fast because we keep `revisionHistoryLimit: 5` and our images are immutable. With `maxUnavailable: 0`, even a failed rollout doesn't take the service down — the old ReplicaSet stays scaled until the new one is healthy. We just `rollout undo` and the old ReplicaSet scales back up."*

---

# 5. How High Availability Was Ensured

## 5.1 The failure model (lead with this — it shows structured thinking)

| Failure | Frequency | Mitigation |
|---|---|---|
| Single pod crash | Hourly across fleet | ReplicaSet recreates; readiness drains traffic |
| Node hardware fail | Monthly | Karpenter replaces; pods reschedule via topology spread |
| AZ outage | Yearly | 3-AZ spread, multi-AZ ALB & RDS failover |
| Region outage | Rare | Pilot-light DR in `eu-west-1` (Terraform replay + ECR replication + RDS cross-region snapshots) |
| Bad deploy | Weekly | Rolling update + `maxUnavailable: 0` + auto-rollback on probe failure |
| Voluntary drain (node upgrade) | Monthly | PDB + topology spread keeps service up |
| Control plane upgrade | Quarterly | EKS managed, non-disruptive; we follow with node + add-on upgrades |

## 5.2 Layer-by-layer HA

**Control plane** — AWS-managed across 3 AZs. We pin the EKS version and upgrade quarterly with one-version steps.

**Data plane (nodes)** — managed node group + Karpenter NodePools constrained to 3 AZs. Mix of `m6i.large` on-demand (baseline) + `m6i.large/m6a.large/c6i.large` spot (stateless workloads opt in via toleration).

**Workloads** — `replicas >= 2` enforced by Kyverno; topology spread per-revision (`matchLabelKeys: pod-template-hash`); PDB on every Deployment.

**Networking** — ALB is multi-AZ by default; cross-zone load balancing enabled; `target-type: ip` removes failed pods within 30s.

**Database** — Aurora PostgreSQL multi-AZ writer + 2 readers in other AZs; failover ~30s; RDS Proxy in front to absorb HPA-induced connection storms.

## 5.3 The PDB pattern (with the senior-level field)

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata:
  name: parent-comms-pdb
  namespace: schoolspider-prod
spec:
  maxUnavailable: 1
  selector:
    matchLabels: { app: parent-comms-deployment }
  unhealthyPodEvictionPolicy: IfHealthyBudget    # K8s 1.27+ — KEY DETAIL
```

**Why `unhealthyPodEvictionPolicy: IfHealthyBudget` matters (senior-level):**

- **Old behavior:** PDB blocks eviction of *any* pod, including pods that are stuck/never-Ready. We had a P2 incident where a stuck rollout left a pod in `CrashLoopBackOff` and a node drain was blocked for 6 hours because the PDB counted the broken pod as "in budget".
- **New behavior with `IfHealthyBudget`:** the evictor can remove pods that **never became Ready** even when the budget is technically tight, because they're not contributing to availability anyway.

## 5.4 Topology spread — the per-revision detail

```yaml
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: topology.kubernetes.io/zone
    whenUnsatisfiable: ScheduleAnyway          # soft: prefer AZ spread, allow if impossible
    matchLabelKeys: [pod-template-hash]        # ⭐ per-ReplicaSet spread
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: DoNotSchedule           # hard: never co-locate replicas on same host
    matchLabelKeys: [pod-template-hash]
```

**Without `matchLabelKeys: pod-template-hash`** — during a rolling update, the scheduler counts old + new pods together. New pods can end up piling on one AZ because "the old pods are already spread, so any AZ is fine for the new one." Then if that AZ fails mid-rollout, you lose half your new fleet.

**With it** — spread is computed *per ReplicaSet*. The new ReplicaSet's pods spread across AZs *independently of where the old pods are*. This is **the** detail that separates senior K8s candidates.

## 5.5 What I personally did for HA (claimable)

- *"I migrated topology spread constraints to use `matchLabelKeys: pod-template-hash` after we observed new pods piling on one AZ during a deploy."*
- *"I tuned PDB `unhealthyPodEvictionPolicy` to `IfHealthyBudget` after a stuck rollout blocked a node upgrade for 6 hours."*
- *"I built the runbook for AZ failover testing and ran the first quarterly DR drill — surfaced a CronJob with a hard-coded subnet that wouldn't survive AZ-A loss."*

---

# 6. How Scalability Was Implemented

## 6.1 Three dimensions of scaling

| Dimension | Tool | Trigger |
|---|---|---|
| **Pod horizontal** | HPA + KEDA | CPU, memory, RPS, SQS depth |
| **Pod vertical** | VPA (recommend mode) | Historical actual usage |
| **Node horizontal** | Karpenter | Pending pods, instance flexibility |
| **Database** | Aurora reader autoscaling, RDS Proxy | DB CPU, connection count |

## 6.2 HPA with multi-metric and behavior tuning

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: parent-comms-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: parent-comms-deployment
  minReplicas: 3                  # never below HA floor
  maxReplicas: 20                 # cap to control cost + DB connection burst

  metrics:
    - type: Resource
      resource: { name: cpu,    target: { type: Utilization, averageUtilization: 70 } }
    - type: Resource
      resource: { name: memory, target: { type: Utilization, averageUtilization: 80 } }
    - type: Pods                 # custom metric via Prometheus Adapter
      pods:
        metric: { name: http_requests_per_second }
        target: { type: AverageValue, averageValue: 100 }

  behavior:
    scaleUp:                     # aggressive — react fast to spikes
      stabilizationWindowSeconds: 30
      policies:
        - { type: Percent, value: 100, periodSeconds: 60 }   # double capacity in 60s
        - { type: Pods,    value: 4,   periodSeconds: 60 }   # OR add 4 pods
      selectPolicy: Max
    scaleDown:                   # conservative — avoid flapping
      stabilizationWindowSeconds: 300
      policies:
        - { type: Percent, value: 50, periodSeconds: 60 }    # remove at most 50%/min
      selectPolicy: Max
```

**Senior-level details:**

- **Multi-metric is OR semantics** — whichever metric demands more replicas wins. CPU **or** memory **or** RPS.
- **Custom metrics via Prometheus Adapter** — exposes `http_requests_per_second` from the app's `/actuator/prometheus`. CPU lags real load by ~30s; RPS doesn't.
- **Asymmetric behavior** — scale up in 30s, scale down over 5 min. Mirrors traffic patterns (spikes are fast, decays are slow).
- **`requests` must be set correctly** — HPA computes utilization as `usage / request`. Wrong requests → wrong scaling.

## 6.3 KEDA for event-driven scaling (scale-to-zero)

For batch / queue-driven workloads, HPA isn't enough. KEDA scales on **external metrics**:

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: report-generator
spec:
  scaleTargetRef: { name: report-generator-deployment }
  minReplicaCount: 0           # ⭐ scale to zero when idle
  maxReplicaCount: 30
  triggers:
    - type: aws-sqs-queue
      metadata:
        queueURL: https://sqs.eu-west-2.amazonaws.com/.../report-jobs
        queueLength: "10"      # 1 pod per 10 visible messages
        awsRegion: eu-west-2
      authenticationRef: { name: keda-aws-irsa }
```

**Real impact at School Spider:** the nightly report generator was idle 22 hours/day. Moving it to KEDA scale-to-zero saved **~£600/month** on dev + prod combined.

## 6.4 VPA in recommend mode (right-sizing)

VPA runs in `updateMode: Off` — it observes actual usage and recommends `requests/limits`, but **doesn't auto-update** (because that conflicts with HPA on CPU). We surface VPA recommendations in a Grafana dashboard and review monthly.

**Real impact:** ~30% CPU request reduction across the fleet after the first review cycle, without any availability impact.

## 6.5 Karpenter (node autoscaler) — chosen over Cluster Autoscaler

| | Cluster Autoscaler | Karpenter |
|---|---|---|
| Speed | 1–2 min (ASG warm pool) | 30–45s (direct EC2 RunInstances) |
| Instance types | One per ASG | Many per NodePool, picks cheapest fitting |
| Spot diversification | Manual (multiple ASGs) | Native (`spot` capacity type) |
| Bin-packing / consolidation | No | Yes — replaces underused nodes |

**Our NodePools:**

```yaml
# Baseline (on-demand, predictable)
spec:
  template:
    spec:
      requirements:
        - { key: karpenter.sh/capacity-type, operator: In, values: [on-demand] }
        - { key: node.kubernetes.io/instance-type, operator: In, values: [m6i.large, m6i.xlarge] }
  limits: { cpu: "100" }
  disruption:
    consolidationPolicy: WhenUnderutilized
    consolidateAfter: 30s

# Spot (best-effort, stateless workloads via toleration)
spec:
  template:
    spec:
      taints: [{ key: capacity, value: spot, effect: NoSchedule }]
      requirements:
        - { key: karpenter.sh/capacity-type, operator: In, values: [spot] }
        - { key: node.kubernetes.io/instance-type, operator: In,
            values: [m6i.large, m6a.large, m5.large, c6i.large, c6a.large] }
```

**Real impact:** node provisioning cut from ~2 min to ~40s; ~25% compute cost reduction via consolidation.

## 6.6 What I personally did for scaling (claimable)

- *"I migrated us from Cluster Autoscaler to Karpenter. Cut node provisioning from ~2 min to ~40s and reduced compute cost ~25% via consolidation. Wrote the migration runbook with both running side-by-side during cutover."*
- *"I added the custom-metrics HPA driven by `http_requests_per_second` from Prometheus Adapter. CPU was a lagging indicator — by the time CPU showed load, P95 latency was already breached."*
- *"I introduced KEDA + scale-to-zero for our nightly report generator. Saved ~£600/month — the workload was idle 22 hours a day."*
- *"I set up RDS Proxy after a cold-start incident: HPA scaled order-service from 2 to 12 pods during a flash sale, each pod opened 10 connections, Aurora hit max_connections, all writes failed for 90 seconds."*

---

# 7. How Security Was Implemented (defense in depth)

## 7.1 Threat model (lead with this)

| Attack vector | Control |
|---|---|
| Stolen pod = stolen AWS creds | IRSA — no static keys, narrow IAM policy per workload |
| Container escape | Non-root + drop ALL caps + readOnlyRootFS + seccompRuntimeDefault + no priv-escalation |
| Compromised image | Trivy scan in CI (fail HIGH/CRITICAL) + ECR enhanced scan + cosign signing + Kyverno verifyImages |
| Lateral movement | NetworkPolicy default-deny per ns + RBAC least-privilege |
| Stolen K8s credentials | Short-lived projected SA tokens, audit logging |
| Secrets in Git | ESO + Secrets Manager + .gitignore + gitleaks pre-commit |
| Cluster API exposure | Private endpoint + public CIDR allowlist, VPN-only |
| Data exfil via egress | NetworkPolicy egress rules + VPC interface endpoints (no Internet for AWS APIs) |

## 7.2 Pod & container security context

Already shown in §3.1. The full hardened version:

```yaml
# Pod-level
securityContext:
  runAsNonRoot: true
  runAsUser: 10001
  runAsGroup: 10001
  fsGroup: 10001
  seccompProfile: { type: RuntimeDefault }

# Container-level
securityContext:
  allowPrivilegeEscalation: false
  readOnlyRootFilesystem: true
  capabilities: { drop: ["ALL"] }

# Compensation for read-only root
volumes: [{ name: tmp, emptyDir: {} }]
volumeMounts: [{ name: tmp, mountPath: /tmp }]
```

## 7.3 Pod Security Standards (the modern enforcement)

PSS replaces the deprecated PodSecurityPolicy. Enabled per-namespace via labels:

```powershell
kubectl label ns schoolspider-prod `
  pod-security.kubernetes.io/enforce=restricted `
  pod-security.kubernetes.io/enforce-version=v1.30 `
  pod-security.kubernetes.io/audit=restricted `
  pod-security.kubernetes.io/warn=restricted
```

**`restricted` profile** blocks (at admission):
- `privileged: true`
- `hostNetwork`, `hostPID`, `hostIPC`
- `hostPath` volumes
- Running as root
- Capabilities other than `NET_BIND_SERVICE`
- AppArmor / SELinux escape

This runs **inside the API server** as a built-in admission plugin — no operator needed.

## 7.4 NetworkPolicy default-deny (this is missing from your original doc)

By default, K8s allows **all** pod-to-pod traffic. We default-deny, then explicit-allow.

```yaml
# Step 1: default-deny in every app namespace
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: default-deny
  namespace: schoolspider-prod
spec:
  podSelector: {}              # match all pods
  policyTypes: [Ingress, Egress]
---
# Step 2: allow DNS for everyone
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: allow-dns
  namespace: schoolspider-prod
spec:
  podSelector: {}
  policyTypes: [Egress]
  egress:
    - to: [{ namespaceSelector: { matchLabels: { kubernetes.io/metadata.name: kube-system } } }]
      ports: [{ protocol: UDP, port: 53 }, { protocol: TCP, port: 53 }]
---
# Step 3: per-service allow rules
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: parent-comms-allow
  namespace: schoolspider-prod
spec:
  podSelector: { matchLabels: { app: parent-comms-deployment } }
  policyTypes: [Ingress, Egress]
  ingress:
    - from:                          # only ALB target group SG
        - ipBlock: { cidr: 10.0.0.0/16 }       # VPC CIDR (ALB ENIs)
      ports: [{ protocol: TCP, port: 8080 }]
  egress:
    - to:                            # allow Aurora
        - ipBlock: { cidr: 10.0.10.0/24 }      # private DB subnet A
        - ipBlock: { cidr: 10.0.20.0/24 }      # private DB subnet B
      ports: [{ protocol: TCP, port: 5432 }]
    - to:                            # allow other internal service
        - podSelector: { matchLabels: { app: notification-service-deployment } }
      ports: [{ protocol: TCP, port: 8080 }]
```

**How we rolled this out without breaking traffic:**
1. Audited actual flows via VPC flow logs for 1 week.
2. Wrote NetworkPolicies in each namespace.
3. Applied default-deny **last**, after all allow rules were in place.

## 7.5 IRSA — the production-grade pattern

Already covered in §3.5. The interview answer:

> *"Each workload that needs AWS access has its own IAM role. The role's trust policy uses an exact-match on `system:serviceaccount:<ns>:<sa>` — not a wildcard. The IAM policy is scoped to specific resource ARNs — not `*`. So even if `parent-comms` is compromised, the attacker can only publish to that one SNS topic, not access S3 or DynamoDB or other services. The EKS Pod Identity Webhook injects a projected token volume; the AWS SDK calls `sts:AssumeRoleWithWebIdentity` automatically — no static keys anywhere in the image, env vars, or volume mounts."*

## 7.6 External Secrets Operator + AWS Secrets Manager

```
AWS Secrets Manager  ──KMS CMK──►  ESO controller  ──IRSA──►  K8s Secret  ──envFrom──►  Pod
       ▲                                ▲
       │                                │ refresh: 1h
   manual rotation
   or rotation Lambda
```

**Why this beats `secret.yaml` in Git:**
1. **Rotation is decoupled from deploys** — update the value in AWS Secrets Manager, ESO syncs within 1h, `kubectl rollout restart` to pick up new env vars.
2. **CMK-encrypted at rest** in AWS, KMS-encrypted at rest in etcd via EKS encryption config.
3. **Audit trail** — CloudTrail logs every `GetSecretValue` call.
4. **No git-history exfil risk** — old values never touched Git.
5. **IAM-scoped access** — ESO's IRSA role can only read `schoolspider/<env>/*`.

**The cluster-wide store:**
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ClusterSecretStore
metadata: { name: aws-secrets-manager }
spec:
  provider:
    aws:
      service: SecretsManager
      region: eu-west-2
      auth:
        jwt:
          serviceAccountRef: { name: external-secrets, namespace: external-secrets }
```

**Per-service ExternalSecret:**
```yaml
apiVersion: external-secrets.io/v1beta1
kind: ExternalSecret
metadata: { name: parent-comms-secret, namespace: schoolspider-prod }
spec:
  refreshInterval: 1h
  secretStoreRef: { name: aws-secrets-manager, kind: ClusterSecretStore }
  target: { name: parent-comms-secret, creationPolicy: Owner }
  dataFrom:
    - extract: { key: schoolspider/prod/parent-comms }
```

## 7.7 etcd encryption at rest

> *"Kubernetes Secrets are base64-encoded, **not** encrypted. Without etcd encryption at rest, anyone who reads the etcd store reads everything. We enable EKS KMS encryption at cluster creation with a customer-managed CMK — that way every Secret API write is envelope-encrypted before persistence."*

## 7.8 RBAC — separation of duties

We have **separate roles** for separate purposes:

- **Jenkins build agent** (in `jenkins-cicd-agents` ns) — can spawn pods + read configmaps in *that* namespace. Cannot touch app namespaces.
- **CD pipeline** (in app ns) — can `get/list/watch/update/patch` Deployments + read RS/Pods/Events/CMs/Services. **Explicitly excluded:** secrets, serviceaccounts, roles/rolebindings, namespaces, networkpolicies, ingresses.
- **Humans** — never `cluster-admin`. SSO → IAM → EKS Access Entry → K8s group → Role. Break-glass `platform-emergency` requires PR + ticket + Slack approval.

**EKS Access Entries** (modern aws-auth replacement):
```hcl
resource "aws_eks_access_entry" "deployer" {
  cluster_name      = aws_eks_cluster.main.name
  principal_arn     = aws_iam_role.ci_agent.arn
  kubernetes_groups = ["schoolspider-deployers"]   # mapped to RoleBinding
  type              = "STANDARD"
}
```

## 7.9 Admission control — Kyverno

Baseline policies enforced at admission time (PR can't merge a violation, kubectl can't apply a violation):

```
1. require-resource-limits          # no requests/limits → reject
2. disallow-latest-tag              # tag must not be :latest or empty
3. disallow-privileged              # privileged, hostNet, hostPID → reject
4. require-non-root                 # runAsNonRoot: true mandatory
5. verify-images                    # cosign signature must verify
6. restrict-image-registries        # only our ECR account
7. require-labels                   # app, team, env, cost-center labels mandatory
8. auto-generate-pdb                # generate a default PDB if missing
9. require-network-policy           # ns must have default-deny NP
```

## 7.10 Image security pipeline

```
mvn build → Trivy scan (fail on HIGH/CRITICAL with fix available)
         → Cosign sign with KMS key
         → Push to ECR (IMMUTABLE tags)
         → ECR enhanced scanning (Inspector V2, continuous)
         → Kyverno verifyImages on admission (rejects unsigned)
         → Deployed
```

## 7.11 Runtime security

- **Falco** (or AWS GuardDuty EKS Protection) for runtime threat detection — alerts on shell-in-container, sensitive-file-read, unexpected outbound.
- **EKS audit logs** → CloudWatch → SIEM. Alerts on `get/list secrets` from unexpected SAs, `exec/attach` to prod pods outside change windows, RBAC changes.

## 7.12 What I personally did for security (claimable)

- *"I led the migration from plaintext `secret.yaml` files in Git to External Secrets Operator + AWS Secrets Manager. Designed the KMS CMK + IRSA + ClusterSecretStore wiring, wrote the cutover runbook, removed ~15 plaintext secrets from Git history with `git filter-repo`, and rotated every value because Git history must be assumed compromised."*
- *"I rolled out NetworkPolicy default-deny across all app namespaces. Did it in audit mode first via VPC flow logs, mapped the actual flows, then wrote tight allow rules per service. Caught two undocumented integrations in the process."*
- *"I added Kyverno with the 9 baseline policies after a junior engineer accidentally deployed a privileged container. Now PRs that would violate the baseline fail in CI before they reach the cluster."*

---

# 8. How Fault Tolerance Was Implemented

## 8.1 The probe trio (always asked — get this right)

| Probe | Question | If it fails... |
|---|---|---|
| **startupProbe** | "Has the JVM finished warming up?" | Disables the other two until it passes; then K8s forgets it |
| **readinessProbe** | "Should this pod receive traffic *right now*?" | Pod removed from Service endpoints; **not restarted** |
| **livenessProbe** | "Is the process deadlocked?" | kubelet **kills and restarts** the container |

**The Spring Boot Actuator pattern (production-grade):**

```yaml
startupProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }
  failureThreshold: 30          # 30 × 5s = 150s grace
  periodSeconds: 5
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: 8080 }    # ← different endpoint
  initialDelaySeconds: 10
  periodSeconds: 10
  failureThreshold: 3
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: 8080 }     # ← back to liveness
  initialDelaySeconds: 60
  periodSeconds: 30
  failureThreshold: 3
```

**Why separate readiness vs liveness Actuator endpoints (THE senior detail):**

- **`/actuator/health/readiness`** — checks DB pool healthy, downstream services reachable. *Should* fail during startup, during DB blips, during graceful shutdown.
- **`/actuator/health/liveness`** — checks "is the process responsive?" Should *only* fail if the JVM is genuinely stuck.
- **If you point both probes at `/actuator/health`** (the default aggregate), a slow DB call fails liveness too → kubelet restarts the pod → outage. We had this exact P1 incident before fixing it.

## 8.2 Graceful shutdown — the zero-5xx pattern

```yaml
spec:
  terminationGracePeriodSeconds: 60
  containers:
    - lifecycle:
        preStop:
          exec: { command: ["sh","-c","sleep 15"] }
```

**The sequence on pod delete:**
1. K8s marks pod `Terminating`; readiness probe is forced to fail.
2. kube-proxy / AWS LB Controller remove pod from Service endpoints + ALB target group.
3. **`preStop sleep 15`** runs — gives ALB time to deregister this pod IP (default deregistration delay is 300s, we tune to 30s).
4. SIGTERM sent to container.
5. Spring Boot's `server.shutdown=graceful` (set in `application.yaml`) stops accepting new connections, waits up to 30s for in-flight requests.
6. If still alive after `terminationGracePeriodSeconds: 60`, SIGKILL.

**The `preStop sleep 15` is the trick that eliminates 5xxs during deploy.** Without it, the ALB still routes to the pod for ~30s after K8s removed it from endpoints, and those requests fail with `connection refused`.

## 8.3 Self-healing primitives

| Failure | Detector | Response |
|---|---|---|
| Container OOM | kubelet | Restart per `restartPolicy: Always` |
| Process deadlock | livenessProbe | kubelet kills & restarts container |
| Pod fails readiness | readinessProbe | Removed from Service endpoints; not killed |
| Pod evicted (node pressure) | kubelet eviction | ReplicaSet recreates elsewhere |
| Node fails | node-controller | Pods marked for deletion after 5 min, ReplicaSet recreates |
| Node disappears (Karpenter) | Karpenter | Cordons, drains respecting PDB, terminates EC2, replaces |
| Bad image push | ImagePullBackOff | New ReplicaSet stays at 0 ready, rollout fails progressDeadline |
| RDS failover | Aurora event | App reconnects on next query (HikariCP retry) |

## 8.4 App-level fault tolerance (beyond K8s primitives)

- **Resilience4j circuit breakers** on outbound HTTP between services. Open circuit short-circuits to fallback (cached response or degraded mode).
- **Retries with exponential backoff** (Spring Retry / Resilience4j).
- **Bulkheads** — separate thread pools per downstream so one slow dependency doesn't exhaust all threads.
- **Idempotency keys** on POST endpoints so retries are safe.
- **DLQ on every SQS queue** with `maxReceiveCount: 3`. CloudWatch alarm on `ApproximateNumberOfMessagesVisible > 0` on DLQ — that's the actionable signal that logic is broken (main queue depth growing under load is normal).
- **Async failures don't fail user requests** — e.g., signup returns 201 even if welcome-email SNS publish fails. Signup is durable in Aurora; emailing is best-effort.

## 8.5 What I personally did for FT (claimable)

- *"I owned the rollout of `preStop` hooks and graceful shutdown across all services after we measured ~50 5xx errors per deploy from dropped connections. After the change, deploys are clean."*
- *"I tuned the probe configurations service-by-service after a P1 incident — a too-aggressive liveness probe killed a pod during a slow GC pause. Now we have separate Spring Actuator endpoints and startup probes everywhere."*
- *"I introduced Resilience4j circuit breakers for the order→cart synchronous call after a cart-service incident took down checkout. Now order-service degrades to a cached cart instead of failing."*

---

# 9. How Production Traffic Was Routed

```
End user
   ↓
Route 53 (DNS)
   ↓
CloudFront (TLS termination, edge caching for static assets, WAF managed rules)
   ↓
AWS ALB (single shared via IngressGroup)
   ↓
Listener rule (priority-ordered by group.order)
   ↓
Target group (target-type: ip → registered pod IPs)
   ↓
Pod (8080)
```

**Health check chain:**
- **CloudFront origin health** — passive (failed origin requests open the circuit).
- **ALB target health** — `/api/<service>/health` every 15s, healthy after 2/2, unhealthy after 2/2 → 30s detection.
- **K8s readiness probe** — `/actuator/health/readiness` every 10s, drives Service endpoints which AWS LB Controller mirrors to the target group.
- **K8s liveness probe** — `/actuator/health/liveness` every 30s, drives kubelet restart.

**Cross-zone load balancing** is enabled on the ALB target group so traffic is distributed evenly across pods regardless of which AZ the ALB ENI received the request in.

---

# 10. How Environment Separation Was Handled

| Environment | Cluster | Namespace | Replicas | HPA min/max | DB |
|---|---|---|---|---|---|
| dev | `schoolspider-dev` | `schoolspider-dev` | 1 | 1/3 | Aurora Serverless v2 (1–4 ACU) |
| staging | `schoolspider-staging` | `schoolspider-staging` | 2 | 2/6 | Aurora provisioned (small) |
| prod | `schoolspider-prod` | `schoolspider-prod` | 3 | 3/20 | Aurora multi-AZ + 2 readers |

**Same image** (same digest, ideally) is promoted across environments. **Different ConfigMap, Secret, replicas, HPA bounds, ingress hosts**.

The proper enterprise pattern (which we're moving toward): **Kustomize overlays** — a `base/` with common manifests + `overlays/dev|staging|prod` that patch what's different. Or **Helm charts** with per-env values files. We currently have per-env folders with full duplication; Kustomize migration is on the roadmap.

---

# 11. How Monitoring & Troubleshooting Was Handled

## 11.1 The structured triage flow

```
1. Pod-level    → kubectl get/describe/logs
2. Deployment   → kubectl rollout status/history
3. Service      → kubectl get endpoints (empty endpoints = problem)
4. Ingress/ALB  → kubectl describe ingress + AWS LB Controller logs + ALB target health
5. DNS          → kubectl exec <pod> -- nslookup <svc>.<ns>.svc.cluster.local
6. Resources    → kubectl top pod/node
7. Application  → app logs, traces, downstream dependency status
```

## 11.2 The kubectl commands you must know cold

```powershell
# Triage
kubectl get pods -n <ns> -o wide
kubectl describe pod <pod> -n <ns>
kubectl logs <pod> -n <ns> --previous              # crash loop
kubectl logs -f <pod> -n <ns> -c <container>
kubectl get events -n <ns> --sort-by=.lastTimestamp

# IRSA debugging
kubectl exec <pod> -n <ns> -- env | Select-String AWS
kubectl exec <pod> -n <ns> -- aws sts get-caller-identity

# Rollout
kubectl rollout status  deployment/<name> -n <ns>
kubectl rollout history deployment/<name> -n <ns>
kubectl rollout undo    deployment/<name> -n <ns> --to-revision=3
kubectl rollout restart deployment/<name> -n <ns>      # picks up rotated Secret

# Networking
kubectl get endpoints <svc> -n <ns>                    # empty = no Ready pods
kubectl exec <pod> -n <ns> -- nslookup <svc>.<ns>.svc.cluster.local
kubectl exec <pod> -n <ns> -- curl -sv http://<svc>:80/health
kubectl get networkpolicy -n <ns>

# Day-2
kubectl top pod -A --sort-by=cpu
kubectl drain <node> --ignore-daemonsets --delete-emptydir-data
kubectl debug -it <pod> -n <ns> --image=busybox --target=<container>

# Stuck resources
kubectl patch pod <pod> -n <ns> -p '{\"metadata\":{\"finalizers\":[]}}' --type=merge
kubectl delete pod <pod> -n <ns> --force --grace-period=0
```

## 11.3 Production observability stack

- **Metrics** — kube-prometheus-stack (Prometheus + Grafana), ServiceMonitor per workload scraping `/actuator/prometheus`.
- **Logs** — Fluent Bit DaemonSet → CloudWatch Logs / Loki, structured JSON from Spring Boot.
- **Traces** — OpenTelemetry Java agent → AWS X-Ray (or Tempo).
- **Alerts** — Alertmanager → PagerDuty for SLO burn rate (e.g., 5xx > 0.1% over 5 min).
- **SLOs** — 99.9% availability per service (43 min downtime/month), p95 latency 200ms reads / 500ms writes.

---

# 12. Common Production Issues (with full triage)

## 12.1 ImagePullBackOff

**Causes:** wrong image/tag, ECR auth, node IAM missing ECR read, lifecycle policy expired the tag, region mismatch.

**Triage:**
```powershell
kubectl describe pod <pod> -n <ns>                               # event tells you which
aws ecr describe-images --repository-name <repo> --region <region>
```

## 12.2 CrashLoopBackOff

**Causes:** app exception on startup, missing env var, DB unreachable, missing Secret ref, port mismatch, OOMKilled (check `lastState`).

**Triage:**
```powershell
kubectl logs <pod> -n <ns> --previous
kubectl get pod <pod> -n <ns> -o jsonpath='{.status.containerStatuses[0].lastState.terminated.reason}'
```

## 12.3 Pod Pending

**Causes:** insufficient resources, taint mismatch, node affinity, topology spread `DoNotSchedule`, PVC unbound, autoscaler delay.

**Triage:**
```powershell
kubectl describe pod <pod> -n <ns>     # Events section
kubectl get nodes
kubectl describe node <node>           # Allocatable + Allocated resources
```

## 12.4 Service has no endpoints

**Causes:** selector mismatch with pod labels, all pods failing readiness, no pods running.

**Triage:**
```powershell
kubectl get endpoints <svc> -n <ns>                 # if empty, dig further
kubectl get pods -n <ns> --show-labels              # check labels match selector
kubectl get pods -n <ns> -o wide                    # check pod readiness
```

## 12.5 ALB target unhealthy

**Causes:** wrong health check path, app port mismatch, security group blocking, app not yet ready.

**Triage:**
```powershell
kubectl logs -n kube-system deployment/aws-load-balancer-controller
kubectl describe ingress <name> -n <ns>
# Then in AWS console: Target Group → unhealthy targets → Reason
```

## 12.6 IRSA giving 403/AccessDenied

**Causes (in order):** SA annotation wrong/missing, pod started before annotation existed, IAM trust policy mismatch, IAM policy doesn't allow the action, OIDC provider drift.

**Triage:**
```powershell
kubectl get sa <sa> -n <ns> -o yaml                              # annotation present?
kubectl get pod <pod> -o yaml | Select-String serviceAccountName
kubectl exec <pod> -n <ns> -- env | Select-String AWS
kubectl exec <pod> -n <ns> -- aws sts get-caller-identity        # which role?
# CloudTrail in AWS — find the denied call, confirm role + resource ARN
```

---

# 13. Node Drain, Cluster Upgrade, Node Group Upgrade

## 13.1 Node drain — the safe pattern

```powershell
kubectl cordon <node>                                      # mark unschedulable first
kubectl drain <node> --ignore-daemonsets --delete-emptydir-data --grace-period=60
```

**What happens:**
1. Cordon — no new pods land here.
2. Drain — eviction API call per pod.
3. **Eviction respects PDB** — if `maxUnavailable: 1` and only 1 pod available, drain pauses.
4. With `unhealthyPodEvictionPolicy: IfHealthyBudget`, stuck pods are still evictable.
5. `--ignore-daemonsets` — DaemonSets restart on node lifecycle, expected.
6. `--delete-emptydir-data` — explicit acknowledgement that scratch data is lost.

## 13.2 EKS cluster upgrade — the senior-level steps

1. **Read deprecation notes** for the target version.
2. **Run deprecation scanner**: `pluto detect-helm`, `kubectl deprecations`, `kubent`.
3. **Upgrade control plane first** — `aws eks update-cluster-version`. Non-disruptive; K8s supports one-version skew between control plane and kubelets.
4. **Upgrade managed add-ons** — VPC CNI, CoreDNS, kube-proxy to compatible versions.
5. **Upgrade node groups** — managed node group rolling replacement, **respects PDBs**, drains and replaces nodes one at a time.
6. **Validate** — smoke tests, error rate dashboards, alert silence verification.
7. **Always one minor version at a time. Never skip.**

## 13.3 Why we use Karpenter for most node lifecycle

Karpenter handles drain/replace automatically:
- Detects underutilized nodes via consolidation policy.
- Cordons + drains respecting PDB.
- Terminates EC2 instance + replaces with cheaper instance if available.
- We use a `karpenter.sh/do-not-disrupt: "true"` annotation for stateful pods that must not be moved.

---

# 14. PriorityClass and Preemption

We define **4 tiers**:

```yaml
schoolspider-critical = 1000000   # auth, payment, gateway
schoolspider-high     = 100000    # parent comms, reporting
schoolspider-medium   = 10000     # internal dashboards
schoolspider-low      = 100       # batch / cleanup jobs (preemptionPolicy: Never)
```

Under cluster CPU/memory pressure, the scheduler can preempt lower-priority pods to schedule higher-priority pending pods. The `low` class has `preemptionPolicy: Never` — batch jobs don't preempt anyone.

`globalDefault: false` on all of them — pods must opt in explicitly. Only one PriorityClass in a cluster can be `globalDefault: true`, and we don't want surprise priority on every workload.

---

# 15. Disaster Recovery (this section is missing from your original)

| Scenario | RTO | RPO | Mechanism |
|---|---|---|---|
| Pod failure | <1 min | 0 | ReplicaSet recreates |
| Node failure | <5 min | 0 | Karpenter + topology spread |
| AZ failure | <5 min | 0 | 3-AZ ALB + EKS + Aurora multi-AZ |
| Region failure | ~4 h | ~5 min | Pilot-light DR in `eu-west-1` |
| Bad deploy | <2 min | 0 | Rolling update + `rollout undo` |
| etcd corruption | AWS-managed | AWS-managed | EKS responsibility |
| Accidental namespace delete | ~30 min | <24 h | Velero restore from S3 |

**DR mechanisms in detail:**
- **Velero** scheduled backups of namespace state (manifests + PVC snapshots) to S3, daily, 30-day retention.
- **Aurora**: continuous PITR (5-min RPO) + cross-region snapshot copy to `eu-west-1` (1-hour RPO).
- **Container images**: ECR cross-region replication to `eu-west-1`.
- **Terraform state**: S3 versioning + DynamoDB lock; remote backend in a DR-protected account.
- **Quarterly DR drill** — actual failover to `eu-west-1` for one non-critical workload. Tested DR is the only DR that counts.

---

# 16. Cost Optimization (a question you'll get)

| Lever | Impact at School Spider |
|---|---|
| Single shared ALB via IngressGroup vs per-service ALBs | ~£40/month per env (we have 12+ services) |
| Karpenter consolidation vs Cluster Autoscaler | ~25% node hours |
| Spot for stateless workloads | ~70% on those nodes |
| HPA scale-down off-hours + KEDA scale-to-zero for batch | Variable, often >40% on dev |
| VPA right-sizing recommendations (monthly review) | ~30% CPU request reduction |
| VPC interface endpoints (PrivateLink for AWS APIs) | NAT data fees eliminated for AWS API calls |
| Aurora Serverless v2 in dev | Scales to 1 ACU when idle |
| Cluster shutdown in dev outside business hours | ~60% on dev compute |

---

# 17. Complete Enterprise-Level Story (use this as your main long answer)

> *"At School Spider — an EdTech SaaS used by UK schools and part of IRIS — we run our application on Amazon EKS in `eu-west-2` across three AZs. The platform is mission-critical during school hours, so the K8s setup is built around four non-negotiables: high availability, security, fault tolerance, and scalability.*
>
> *Each microservice ships with the same baseline contract — Deployment, Service, Ingress, ConfigMap, ExternalSecret, ServiceAccount, HPA, PDB, NetworkPolicy. The contract specifies replicas ≥ 2, RollingUpdate with maxUnavailable: 0 and maxSurge: 1, progressDeadlineSeconds 600, hardened SecurityContext at both pod and container level — runAsNonRoot UID 10001, drop ALL caps, readOnlyRootFilesystem, seccomp RuntimeDefault, allowPrivilegeEscalation false. We enforce this baseline at admission with Kyverno — non-conforming manifests are rejected before they hit etcd.*
>
> ***On HA*** *— it's not just replica count. We use TopologySpreadConstraints with `matchLabelKeys: pod-template-hash` so spread is computed per-ReplicaSet during a rolling update, not across old and new pods together. PDBs use `unhealthyPodEvictionPolicy: IfHealthyBudget` so stuck pods don't block node drains. Aurora is multi-AZ with reader autoscaling. ALB has cross-zone load balancing enabled. We run quarterly DR drills against a pilot-light environment in `eu-west-1`.*
>
> ***On security*** *— defense in depth. Pod Security Standards `restricted` profile enforced at namespace level. NetworkPolicy default-deny in every app namespace, with explicit allow rules per service mapped from VPC flow log audits. IRSA scoped per workload — exact-match on `system:serviceaccount:<ns>:<sa>`, IAM policies scoped to specific resource ARNs not wildcards. Secrets via External Secrets Operator from AWS Secrets Manager, encrypted at rest with a customer-managed KMS CMK, with etcd KMS encryption enabled at the cluster level — base64 isn't encryption. Image security: Trivy scan in CI, cosign signing with KMS, ECR enhanced scanning continuous, Kyverno verifyImages on admission to reject unsigned images. EKS audit logs to CloudWatch with alerts on `get secrets` from unexpected SAs.*
>
> ***On fault tolerance*** *— Spring Boot Actuator with separate `/actuator/health/readiness` and `/actuator/health/liveness` endpoints, so a slow DB call doesn't trigger a pod restart. startupProbe with 150s grace for JVM warmup. preStop sleep 15 + ALB deregistration_delay 30s + terminationGracePeriodSeconds 60 — that combination eliminates 5xxs during deploys. App-level: Resilience4j circuit breakers between services, retries with exponential backoff, DLQs on every SQS queue with CloudWatch alarms on DLQ depth.*
>
> ***On scalability*** *— three dimensions. HPA with multi-metric (CPU + memory + custom RPS via Prometheus Adapter), aggressive scale-up window 30s, conservative scale-down 5min. KEDA for event-driven workloads with scale-to-zero — saved us roughly £600/month on the nightly report generator alone. Karpenter for node autoscaling — picked over Cluster Autoscaler because consolidation cuts compute cost ~25% and provisioning latency dropped from ~2min to ~40s. RDS Proxy in front of Aurora to absorb HPA-induced connection storms. VPA in recommend mode to right-size requests monthly.*
>
> *I personally owned the IRSA + ESO + Secrets Manager migration, the NetworkPolicy default-deny rollout, the Kyverno baseline policies, the Karpenter migration from Cluster Autoscaler, and the probe/preStop tuning that took our deploy-time 5xxs to zero. The way we keep all of this uniform across teams is by making the baseline the platform contract — Kyverno enforces it at admission, so non-conforming workloads simply can't deploy. That's how three platform engineers can support 12+ services without firefighting."*

---

# 18. Short Version (60 seconds)

> *"At School Spider, we run on EKS across 3 AZs in `eu-west-2`. Every service ships with the same baseline: Deployment with maxUnavailable: 0 and maxSurge: 1, hardened SecurityContext, three separate Spring Actuator probes, IRSA-scoped SA, HPA, PDB with `IfHealthyBudget`, NetworkPolicy default-deny, ExternalSecret from AWS Secrets Manager. We enforce this at admission via Kyverno. HA comes from per-ReplicaSet topology spread, PDB, and 3-AZ ALB + Aurora. Scalability is HPA + Karpenter + KEDA — Karpenter cut compute ~25% and KEDA scale-to-zero saves ~£600/month. Security is IRSA + NetworkPolicy + PSS restricted + Kyverno + etcd KMS encryption. Fault tolerance is the probe trio + preStop sleep 15 to handle ALB deregistration + Resilience4j circuit breakers + DLQs."*

---

# 19. Anticipated Cross-Questions & 30-Second Answers

| Question | Answer |
|---|---|
| Why EKS over ECS? | K8s ecosystem (Helm, GitOps, Karpenter, ESO, service mesh option), portability, hiring pool. ECS locks us into AWS-only tooling. |
| Why not Fargate? | Per-task pricing > EC2 at our scale; no DaemonSet support hurts observability stack; cold-start penalty for HPA scale-up. |
| Why Karpenter over Cluster Autoscaler? | 30s vs 2min provisioning, native consolidation, multi-instance-type per NodePool, native spot diversification. |
| How do you upgrade EKS? | `pluto`/`kubent` for deprecation scan, control plane first (non-disruptive), then add-ons, then node groups via rolling replacement respecting PDBs. One minor version at a time, never skip. |
| Why no service mesh today? | At 12 services we don't have the operational maturity payoff. Would adopt Linkerd at ~20+ services or when mTLS / progressive delivery becomes a hard requirement. |
| Multi-tenancy in one cluster? | Soft tenancy (separate ns + RBAC + NetworkPolicy + ResourceQuota) for trusted teams. Hard tenancy (separate clusters) only if compliance requires it. |
| Multi-region? | Pilot-light DR in `eu-west-1` today. Active-active via Aurora Global DB + Route53 latency routing on the roadmap. |
| GitOps — Argo or Flux? | Argo CD for the UI + multi-cluster app-of-apps pattern. Currently push CD via Jenkins; Argo migration is in progress. |
| Why per-pod IRSA instead of node role? | Node role gives every pod on the node the same permissions. IRSA is per-workload — auth-service can publish to SNS, parent-comms can't. Least-privilege per pod. |
| What's your worst K8s incident? | (Tell a real-shaped story: detection → triage → root cause → fix → prevention. KMS key near-deletion, stuck PDB, probe killing JVM during GC are all good archetypes.) |
| What would you build next? | (1) Argo CD GitOps cutover. (2) Linkerd for mTLS on the payments path. (3) Active-active multi-region. (4) Chaos engineering with AWS Fault Injection Service to validate the FT claims. |
| Why not run the database in K8s? | Stateful workloads add complexity (PV pinning, backup, failover) for no benefit when AWS managed services exist. Aurora handles failover, backups, encryption, scaling for us. |
| How do you handle a pod that's exhausting node memory and OOMKilling neighbors? | Set memory limits on every pod (Kyverno enforces). Understand QoS — `Guaranteed` (requests==limits) is last to evict; we use this for critical pods. Alert on `container_memory_working_set_bytes` near limit. |

---

# 20. Numbers to Memorize (concrete = credible)

| Number | What |
|---|---|
| **3** | AZs, NAT gateways, replicas (prod minimum), Karpenter NodePools |
| **2 → 20** | HPA replica range per service in prod |
| **70% / 80%** | HPA CPU / memory target |
| **30s / 5min** | HPA scale-up / scale-down stabilization windows |
| **100 RPS** | HPA custom-metric target per pod |
| **0 / 1** | maxUnavailable / maxSurge |
| **600s** | progressDeadlineSeconds |
| **60s + sleep 15** | terminationGracePeriodSeconds + preStop |
| **150s** | startupProbe grace (30 × 5s) |
| **30s** | ALB deregistration delay tuned to |
| **1h** | ESO refresh interval |
| **30s** | Karpenter consolidateAfter |
| **3** | SQS maxReceiveCount before DLQ |
| **5 min / 4 h** | RTO for AZ failure / region failure |
| **5 min** | RPO from Aurora continuous backup |
| **12+** | services on the cluster |
| **~25% / ~30% / ~£600/mo** | Karpenter savings / VPA savings / KEDA savings |

---

# 21. Topics to Prepare (your existing list, complete)

1. Pod lifecycle (Pending → Running → Succeeded/Failed; Init/Sidecar/Ephemeral containers)
2. Deployment, ReplicaSet, rolling update mechanics
3. `maxUnavailable` / `maxSurge` / `progressDeadlineSeconds` / `minReadySeconds` / `revisionHistoryLimit`
4. Three probes — when each fires, what each does on failure
5. Service types (ClusterIP / NodePort / LoadBalancer / ExternalName / Headless)
6. Ingress, AWS LB Controller, **IngressGroup**, `target-type: ip`
7. ConfigMap vs Secret (base64 ≠ encryption)
8. External Secrets Operator + AWS Secrets Manager
9. ServiceAccount + IRSA (OIDC trust policy, projected token, AssumeRoleWithWebIdentity)
10. RBAC (Role / ClusterRole / Binding) + EKS Access Entries
11. HPA + metrics-server + Prometheus Adapter custom metrics
12. KEDA for event-driven scaling
13. VPA (recommend mode)
14. Resource requests/limits + QoS classes
15. Cluster Autoscaler vs Karpenter
16. PDB + `unhealthyPodEvictionPolicy: IfHealthyBudget`
17. Node drain (cordon → eviction API → PDB respect)
18. Cluster upgrade strategy (deprecation scan, version skew, one minor at a time)
19. Topology spread + `matchLabelKeys: pod-template-hash`
20. PriorityClass + preemption + `globalDefault: false`
21. **NetworkPolicy default-deny + per-service allow**
22. **Pod Security Standards (`restricted` profile)**
23. **Kyverno admission policies (the 9 baselines)**
24. SecurityContext (pod + container level)
25. Graceful shutdown (preStop + termination grace + ALB deregistration)
26. Spring Boot Actuator separate readiness/liveness endpoints
27. Image security (Trivy + cosign + ECR enhanced + Kyverno verifyImages)
28. etcd KMS encryption at rest
29. Common issues (ImagePullBackOff, CrashLoopBackOff, Pending, no endpoints, ALB unhealthy, IRSA 403)
30. DR strategy (Velero, Aurora cross-region, ECR replication, RTO/RPO)
31. Cost optimization (IngressGroup, Karpenter, KEDA, VPC endpoints, spot)
32. Observability (kube-prometheus-stack, Loki, Fluent Bit, OpenTelemetry, X-Ray)

---

# 22. The Final Strong Statement

> *"In production Kubernetes, availability isn't achieved by increasing replicas. It's achieved by combining the right replica count with topology spread per ReplicaSet, PDBs that don't block themselves, probes that distinguish 'busy' from 'dead', graceful shutdown that handles the ALB deregistration race, autoscaling that's both reactive and predictive, IRSA-scoped per workload, NetworkPolicy default-deny, secrets via ESO with KMS encryption end-to-end, and admission-time policy enforcement so the baseline is automatic, not optional. That's the level of platform we built at School Spider, and it's the level I'd build anywhere."*
