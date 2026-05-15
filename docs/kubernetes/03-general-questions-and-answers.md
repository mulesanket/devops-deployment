# Kubernetes — General Interview Questions & Answers

> Target depth: 3–3.5 YOE DevOps. Answers are tight, project-grounded where useful, and end with a "what most candidates miss" twist where applicable.

---

## A. Core Concepts

### Q1. What is Kubernetes and what problem does it solve?
Kubernetes is a container orchestration platform. It solves the gap between *"I have a container image"* and *"my service is running reliably across multiple servers"*. Specifically:
- **Scheduling** — decides which node a pod runs on.
- **Self-healing** — restarts dead containers, replaces failed pods.
- **Scaling** — horizontal scaling declaratively (`replicas: 5` or HPA).
- **Service discovery & load balancing** — stable virtual IP for a fleet of ephemeral pods.
- **Rolling updates and rollbacks** — declarative deployment strategy.
- **Config & secret management** — separate from image.

Before K8s you'd hand-roll all of this with bash + systemd + HAProxy + Consul. K8s makes it a declarative API.

---

### Q2. Pod vs Container vs Deployment vs ReplicaSet — explain the hierarchy.
- **Container** = a running process from an image. K8s doesn't run containers directly.
- **Pod** = the smallest deployable unit. Usually one container; sometimes a few tightly coupled ones (main + sidecar) sharing network + volumes.
- **ReplicaSet** = "keep N copies of this pod template alive." Rarely created directly.
- **Deployment** = "manage ReplicaSets to roll out new versions." This is what you actually write. A Deployment creates a ReplicaSet, which creates Pods, which run Containers.

When you `kubectl apply` a Deployment with a new image, K8s creates a *new* ReplicaSet for the new spec and gradually scales the old one down — that's how rolling updates work.

---

### Q3. Service types — when to use each?
| Type | Use case |
|---|---|
| **ClusterIP** (default) | Internal service-to-service. Most common. ShopEase auth/product/cart/order are all ClusterIP. |
| **NodePort** | Expose on every node's IP at a static port. Rarely used in production — usually for testing or behind an external LB. |
| **LoadBalancer** | Provision an external cloud LB (NLB/ALB). On EKS without an Ingress Controller, this is per-service which is expensive — one LB per service. |
| **ExternalName** | DNS CNAME alias to an external hostname. Useful for legacy services outside the cluster. |
| **Headless** (`clusterIP: None`) | No virtual IP — DNS returns pod IPs directly. Used for StatefulSets and direct pod addressing. |

Production pattern: ClusterIP for everything + **one Ingress** (ALB) doing path-based routing.

---

### Q4. What's the difference between a ConfigMap and a Secret?
**Functionally similar** — both are key-value stores mounted into pods as env vars or files. **Differences:**
- ConfigMap is stored as plaintext in etcd; Secret is base64-encoded (NOT encrypted by default).
- Some tools/operators treat Secrets specially (e.g., `kubectl get secret` doesn't print values by default).
- **Real encryption** requires enabling KMS encryption at rest for etcd (`encryptionConfig`).
- **Convention** — use Secret for anything sensitive, ConfigMap for everything else.

ShopEase has ConfigMap per service for things like `SPRING_PROFILES_ACTIVE`, `DB_URL`, and ESO-managed Secret per service for passwords and JWT keys.

**What most candidates miss:** base64 is *encoding*, not encryption. Anyone with `get secret` RBAC can read the value. Real protection comes from etcd encryption + RBAC + audit logs.

---

### Q5. What are labels and selectors?
- **Labels** are arbitrary key=value metadata you stick on any object (`app: auth-service`, `tier: backend`, `env: dev`).
- **Selectors** are queries against labels (`app=auth-service,env=dev`).

This is how K8s glues loosely coupled objects:
- A **Service** selects which **Pods** it routes to via label selector.
- A **Deployment** selects which **Pods** it owns.
- A **NetworkPolicy** selects source/dest pods via labels.
- HPA, PDB, topologySpread — all label-based.

**What candidates miss:** Deployment `spec.selector` is **immutable** after creation. Get it wrong and you must delete and recreate.

---

### Q6. Probes — startupProbe, livenessProbe, readinessProbe. Differences?
| Probe | "Is the question..." | If it fails... |
|---|---|---|
| **startupProbe** | "Has the app finished booting?" | Disables the other two until it passes. Then K8s forgets about it. |
| **livenessProbe** | "Is the app still alive (not deadlocked)?" | K8s kills and restarts the container. |
| **readinessProbe** | "Should this pod receive traffic right now?" | Pod is removed from Service endpoints; not restarted. |

**Common mistake:** using only `livenessProbe` for slow-starting apps. Spring Boot takes 30–60 s. A liveness probe with `failureThreshold * periodSeconds < 60` will kill the container before it ever starts.

**ShopEase pattern:** startupProbe with generous `failureThreshold` (covers warmup), then separate liveness (kill-if-deadlocked) and readiness (drain-during-shutdown) hitting different Spring Actuator endpoints.

---

### Q7. What happens, end-to-end, when you run `kubectl apply -f deployment.yaml`?
1. `kubectl` reads the file, talks to the **API server** over HTTPS (auth via kubeconfig).
2. API server **authenticates** (cert/OIDC/token) and **authorizes** via RBAC.
3. **Admission controllers** mutate/validate the object (e.g., inject defaults, enforce policies, IRSA webhook).
4. API server **persists** the object to **etcd**.
5. **Controller Manager** notices a new Deployment → creates a ReplicaSet.
6. ReplicaSet controller → creates Pods (as objects in etcd, no node yet).
7. **Scheduler** watches for unbound pods → assigns each to a node based on requests, affinity, taints.
8. On the chosen node, **kubelet** sees the pod, pulls the image, asks the **container runtime** (containerd) to start the container.
9. **kube-proxy** updates iptables/IPVS rules so the Service IP routes to the new pod.
10. Probes start running. When readiness passes, kube-proxy adds the pod to Service endpoints.

---

### Q8. What is a StatefulSet and when do you use it?
For workloads that need:
- **Stable, predictable identity** — pods are named `pod-0`, `pod-1`, not random.
- **Stable storage** — each pod gets its own PersistentVolume that survives restart and follows the pod's identity.
- **Ordered rollout** — pods come up and shut down in order.

Examples: databases (MySQL, Postgres, Kafka, Elasticsearch), anything where pod identity matters.

**ShopEase uses none** — all four services are stateless. State lives in Aurora MySQL (managed RDS, not in-cluster). That's the right call: don't run databases in K8s unless you have a strong reason.

---

### Q9. Volumes — emptyDir vs hostPath vs PersistentVolume?
- **emptyDir** — temporary scratch space, lives for the pod's lifetime. Use for caches, work directories. Lost on pod restart.
- **hostPath** — mount a directory from the node's filesystem. Dangerous (couples pod to node), used by system DaemonSets only.
- **PersistentVolume (PV)** — cluster-level storage resource (EBS, EFS, NFS). Pods claim a slice via **PersistentVolumeClaim (PVC)**. Survives pod restarts.
- **StorageClass** — defines how PVs are dynamically provisioned (which CSI driver, IOPS, encryption, etc.).

On EKS the **EBS CSI driver** handles `gp3`/`io2` PVs; **EFS CSI driver** handles shared `ReadWriteMany` mounts.

---

### Q10. Namespace — what is it and when to create one?
A namespace is a **virtual cluster** within a cluster. Provides:
- **Name scoping** — two services named `auth-service` in different namespaces are different objects.
- **RBAC boundary** — grant team access only to their namespace.
- **Quota boundary** — `ResourceQuota` caps CPU/memory/pods per namespace.
- **NetworkPolicy boundary** — easy to default-deny cross-namespace traffic.

When to create: per team, per environment (if not separate clusters), per major application. ShopEase uses one namespace per environment (`shopease-webapp-development`).

**Anti-pattern:** namespace per microservice in the same app. Adds RBAC noise without value.

---

## B. Networking

### Q11. How does pod-to-pod networking work?
- Every pod gets a **routable IP** in a flat cluster-wide network — no NAT between pods.
- The **CNI plugin** (VPC CNI on EKS) allocates the IP and wires it up. On EKS specifically, pod IPs come from secondary IPs on the worker node's ENI — they're real VPC IPs, which means security groups, route tables, and VPC flow logs all see them.
- This is why EKS pods can directly hit RDS, S3, etc. with no overlay.
- kube-proxy sets up iptables/IPVS rules so the **Service ClusterIP** virtual IP routes to one of the backend pods.
- **CoreDNS** resolves `<svc>.<ns>.svc.cluster.local` to the Service IP.

---

### Q12. What is an Ingress and how is it different from a Service?
- A **Service** balances traffic *inside* the cluster to a set of pods. L4 (TCP/UDP).
- An **Ingress** is an L7 (HTTP/HTTPS) entry point from *outside* the cluster: hostname + path routing, TLS termination, header rewrites.
- Ingress is **just a spec** — you need an **Ingress Controller** to make it real. On EKS that's typically the **AWS Load Balancer Controller**, which provisions an ALB per Ingress (or shares one across Ingresses with `alb.ingress.kubernetes.io/group.name`).

ShopEase has one Ingress with path rules: `/api/auth/*` → auth-service, `/api/products/*` → product-service, etc. ALB Controller provisions a single ALB for the lot.

---

### Q13. NetworkPolicy — what does it do?
By default K8s allows **all** pod-to-pod traffic. NetworkPolicy is how you restrict it. It's a label-based firewall:

```yaml
# Allow ingress to auth-service only from cart-service and order-service
podSelector: {matchLabels: {app: auth-service}}
ingress:
- from:
  - podSelector: {matchLabels: {app: cart-service}}
  - podSelector: {matchLabels: {app: order-service}}
```

Requires a CNI that implements it (VPC CNI does, via separate enablement; Calico does natively). Without an enforcing CNI, NetworkPolicy YAML applies but does nothing — silent failure.

**ShopEase** has no NetworkPolicy yet — known gap.

---

### Q14. How does DNS work in a cluster?
- **CoreDNS** runs as a Deployment in `kube-system`.
- Every pod's `/etc/resolv.conf` points to CoreDNS's ClusterIP.
- Naming convention: `<service>.<namespace>.svc.cluster.local`. Short forms work within the same namespace.
- Headless services (no ClusterIP) return all pod IPs as A records.
- External names go through CoreDNS's `forward` plugin to upstream resolvers (VPC resolver on EKS).

**Common bug:** pods stuck on stale DNS results because the Java/JVM default DNS cache is "forever". Set `networkaddress.cache.ttl` in `java.security`.

---

## C. Scheduling & Reliability

### Q15. requests vs limits — what's the difference?
- **requests** — the scheduler uses this to decide if a pod fits on a node. It's the *guaranteed minimum*.
- **limits** — the kernel/cgroup caps the container at this value. Over-the-limit memory → OOMKilled. Over-the-limit CPU → throttled.

Pod's **QoS class** is derived from these:
- requests == limits (on all resources) → **Guaranteed** (highest priority, last to evict).
- requests < limits → **Burstable**.
- neither set → **BestEffort** (first to die).

For ShopEase critical services I set both to similar values to get Guaranteed.

---

### Q16. How does the scheduler decide where to put a pod?
Two-phase process:
1. **Filtering** — eliminate nodes that don't fit (insufficient resources, taint mismatch, node selector mismatch, port conflict).
2. **Scoring** — rank remaining nodes by spread, affinity, image locality, resource balance. Highest score wins.

Pod can influence placement via:
- `nodeSelector` — simple label match.
- `affinity / antiAffinity` — soft/hard preferences for nodes or other pods.
- `topologySpreadConstraints` — spread pods across zones/hosts (ShopEase uses this for AZ resilience).
- `tolerations` — opt into running on tainted nodes.

---

### Q17. PriorityClass — what is it and when do you use it?
A `PriorityClass` assigns an integer priority to a pod. The scheduler uses it two ways:
1. When multiple pods are pending, higher-priority pods get scheduled first.
2. Under resource pressure, the scheduler can **preempt** (evict) lower-priority pods to make room for higher-priority ones.

System-critical pods (CoreDNS, kube-proxy) ship with `system-cluster-critical`. For app workloads, define your own — ShopEase has `shopease-critical` for all four services so they outrank batch/internal tools we might add later.

---

### Q18. PodDisruptionBudget — what does it protect against?
PDB protects against **voluntary disruptions** — anything driven by the K8s API:
- `kubectl drain` for node upgrade.
- Cluster Autoscaler scaling down.
- Karpenter consolidation.

It does NOT protect against **involuntary** events: node hardware failure, kernel panic, AZ outage. Those just happen.

A PDB says "at most N pods of this set can be disrupted at once" (or "at least M must remain available"). Drains pause until satisfied.

ShopEase has `minAvailable: 1` per service — node upgrades can't take a whole service down.

---

### Q19. HPA — how does it work?
HorizontalPodAutoscaler:
1. Queries **metrics-server** (or Prometheus Adapter for custom metrics) every 15 s.
2. Computes desired replicas = `ceil(current_replicas * current_metric / target_metric)`.
3. Adjusts the Deployment's `replicas` field. Has stabilization windows to avoid flapping.

**Requires:**
- `metrics-server` running in the cluster (it's an add-on).
- Resource **requests** set on containers (HPA computes utilization as `usage / request`).

**Trap:** if requests are too low, HPA over-scales (high "utilization"). If too high, HPA never scales (low utilization). Right-size requests using real prod data.

---

### Q20. Cluster Autoscaler vs Karpenter — what's the difference?
- **Cluster Autoscaler** scales **node groups** (Auto Scaling Groups). Slow (1–2 min), one instance type per group, simple.
- **Karpenter** is AWS-native, scales by directly launching **EC2 instances** based on pending pod requirements. Faster (~30 s), picks the cheapest fitting instance type, consolidates underused nodes. Modern default for EKS.

ShopEase uses Cluster Autoscaler today (managed node group) — Karpenter is an upgrade I'd do when traffic patterns get spikier.

---

## D. Security

### Q21. RBAC — Role vs ClusterRole vs Binding?
- **Role** — permissions within a namespace.
- **ClusterRole** — permissions cluster-wide (or namespace-scoped resources across all namespaces).
- **RoleBinding** — grants a Role/ClusterRole to a subject (user, group, SA) **within one namespace**.
- **ClusterRoleBinding** — grants cluster-wide.

Subjects can be:
- **User** / **Group** — external identities (IAM, OIDC).
- **ServiceAccount** — for workloads, scoped to a namespace.

**Always prefer:** Role + RoleBinding (least privilege, namespace-scoped). Only use ClusterRoleBinding for cluster admins or system components.

---

### Q22. What is a SecurityContext and what should always be set?
Pod- and container-level fields that influence the Linux security primitives used to run the container. Production minimum:

```yaml
securityContext:
  runAsNonRoot: true              # refuse to start if image's USER is root
  runAsUser: 10001                # specific non-root UID
  runAsGroup: 10001
  fsGroup: 10001
  seccompProfile: {type: RuntimeDefault}   # default syscall filter
containers:
- securityContext:
    allowPrivilegeEscalation: false
    readOnlyRootFilesystem: true
    capabilities:
      drop: ["ALL"]
```

ShopEase has all of these on every Deployment.

---

### Q23. IRSA — explain it and why it beats node-role permissions.
**IAM Roles for Service Accounts** lets a pod assume an IAM role via its K8s ServiceAccount.

**Flow:**
1. EKS creates an OIDC identity provider linked to IAM.
2. You create an IAM role with a trust policy: "trust tokens from this OIDC issuer where `sub == system:serviceaccount:<ns>:<sa>`".
3. K8s SA gets annotation `eks.amazonaws.com/role-arn: <arn>`.
4. EKS Pod Identity Webhook injects env vars + a projected token volume into the pod.
5. AWS SDK calls `sts:AssumeRoleWithWebIdentity` automatically; gets short-lived creds.

**Why better than node role:** node role grants every pod on the node the same permissions. IRSA scopes credentials per workload. Auth-service can publish to SNS; cart-service can't.

---

### Q24. How do you secure secrets in a cluster?
Layered:
1. **Don't put secrets in Git.** Use an external secret store (AWS Secrets Manager, Vault).
2. **Sync via an operator** (External Secrets Operator) so apps consume native K8s Secrets without coupling to the backend.
3. **Encrypt etcd at rest** with a KMS key — without this, anyone with etcd access reads everything.
4. **RBAC** — limit who can `get secrets`. Avoid `view` ClusterRole; it includes secret read.
5. **Audit logs** — alert on secret access from unexpected SAs.
6. **Rotate** — automation triggered on Secrets Manager rotation event.

ShopEase implements steps 1, 2, and 5 (partially). Etcd KMS encryption is the responsibility of EKS — enabled at cluster create.

---

### Q25. Admission Controllers — what are they?
Plugins running inside the API server that intercept every request after authentication/authorization but before persisting to etcd.

Two flavours:
- **Mutating** — modify the request (inject sidecars, defaults, annotations). Example: EKS Pod Identity Webhook injects IRSA env vars.
- **Validating** — accept or reject. Example: PodSecurity admission rejects pods that violate the namespace's security level.

**Policy-as-code tools** that hook in here: **OPA Gatekeeper**, **Kyverno**. Use them to enforce "no `:latest` tags", "must have resource limits", "registry allowlist" cluster-wide.

---

## E. Day-2 Operations

### Q26. How do you do a rollback?
- `kubectl rollout undo deployment/<name>` — rolls back to the previous revision.
- `kubectl rollout undo deployment/<name> --to-revision=N` — specific revision.
- `kubectl rollout history deployment/<name>` — shows what's available.

Deployments keep `spec.revisionHistoryLimit` old ReplicaSets (default 10). ShopEase sets this to 5 to balance history vs etcd noise.

For **Helm releases:** `helm rollback <release> <rev>`.
For **declarative GitOps:** `git revert` + ArgoCD/Flux re-syncs.

---

### Q27. How do you upgrade a cluster version?
EKS pattern (managed):
1. **Read the release notes** — deprecated APIs are the #1 source of pain.
2. **Run a deprecation scanner** — `pluto detect-helm`, `kubectl deprecations`.
3. **Upgrade the control plane** first (`aws eks update-cluster-version`) — non-disruptive, K8s supports one-version skew with kubelets.
4. **Upgrade managed add-ons** (VPC CNI, CoreDNS, kube-proxy) to compatible versions.
5. **Upgrade node groups** — rolling replacement, respects PDBs.
6. **Validate** — smoke tests, dashboards, error rate.

Always one minor version at a time. Never skip versions.

---

### Q28. How do you debug a slow service?
Layered approach:
1. **App-level** — APM (latency, errors), logs around slow requests.
2. **Pod-level** — `kubectl top pod`, look for CPU throttling (`container_cpu_cfs_throttled_seconds_total`).
3. **Network-level** — packet captures with `tcpdump` in a debug pod; check Service endpoint health.
4. **DNS-level** — Pod's app may be hitting CoreDNS for every external call. ndots issue or no DNS cache. Common Java problem.
5. **Downstream** — DB slow queries, third-party APIs.

Tools: Prometheus + Grafana for metrics, Loki/CloudWatch for logs, Jaeger/OpenTelemetry for tracing. Without these, you're flying blind.

---

### Q29. How do you handle stuck or unresponsive resources?
- **Pod stuck Terminating**: usually a finalizer waiting on something. `kubectl get pod -o yaml` → `metadata.finalizers`. Remove with `kubectl patch ... -p '{"metadata":{"finalizers":[]}}' --type=merge`. Force-delete: `kubectl delete pod --force --grace-period=0`.
- **Namespace stuck Terminating**: same — a finalizer on the namespace itself, often a CRD with a finalizer the controller can't process. Same patch trick.
- **PVC stuck**: backing PV's `reclaimPolicy: Retain` means PV stays; delete the PV manually if storage is already gone in AWS.

Use force-delete carefully — finalizers exist for cleanup reasons. Understand what you're skipping.

---

### Q30. What's a sidecar, an init container, an ephemeral container?
- **Init container** — runs to completion before main containers start. Use for setup: schema migrations, downloading config, waiting for a dependency.
- **Sidecar** — runs alongside the main container, sharing the pod's lifecycle. Use for cross-cutting concerns: log shippers, service mesh proxies (Istio's Envoy), secret refreshers.
  - Note: K8s 1.29+ introduced "native" sidecars as a special kind of init container with `restartPolicy: Always`.
- **Ephemeral container** — runtime-added debug container (`kubectl debug`). Doesn't show up in pod spec; used for troubleshooting without restarting the pod.

---

### Q31. What is the difference between a Job and a CronJob?
- **Job** — runs a pod (or a few) to completion. Once done, stays around in completed state. Use for one-off tasks: DB migration, batch import.
- **CronJob** — schedules Jobs on a cron expression. Use for periodic work: nightly backups, report generation.

**Watch out:** CronJob can stack up missed runs. Set `concurrencyPolicy: Forbid` to skip overlapping runs, `startingDeadlineSeconds` to bound retries.

---

### Q32. How does kubectl authenticate?
`kubectl` reads `~/.kube/config` (or `$KUBECONFIG`). The config has:
- **clusters** — API server URL + CA cert.
- **users** — credentials (cert, token, OIDC, or **exec plugin**).
- **contexts** — pair of cluster + user + default namespace.

For EKS, the user uses an **exec plugin** that calls `aws eks get-token` on every API call. The token is a signed STS pre-signed URL valid for ~15 min. The API server verifies it with AWS, maps the resulting IAM identity to a K8s user/group via the `aws-auth` ConfigMap (legacy) or **EKS Access Entries** (current).

---

### Q33. Resource ownership and garbage collection?
Objects have `metadata.ownerReferences`. When the owner is deleted:
- **Foreground deletion** (default for `kubectl delete`): owner waits for dependents to be gone first.
- **Background deletion**: owner deleted first, GC cleans up dependents async.
- **Orphan**: dependents survive (`--cascade=orphan`).

This is how deleting a Deployment cascades to its ReplicaSet and Pods.

---

### Q34. What's the etcd's role and what happens if it dies?
etcd is the **single source of truth** for cluster state. Every object you `kubectl get` lives there. If etcd dies:
- API server returns errors; you can't apply or read changes.
- Already-running pods keep running — kubelet and kube-proxy don't need etcd for steady state.
- Scheduling stops; controller reconciliation stops.

On EKS, etcd is managed by AWS — you don't see it directly. AWS replicates it across 3 AZs and backs it up. Self-managed clusters need to size and back up etcd themselves.

---

## F. Production Patterns

### Q35. What's GitOps and how does it differ from CI/CD push?
- **Traditional CI/CD push**: pipeline has cluster credentials; on every commit, pipeline does `kubectl apply`.
- **GitOps pull**: a controller (ArgoCD, Flux) runs *inside* the cluster, watches a Git repo of manifests, and *pulls* changes to apply. The cluster never gives credentials outward.

**Wins:**
- Git is the audit log; `git revert` is a rollback.
- No long-lived cluster creds in CI.
- Drift detection — controller alerts when live state diverges.
- Onboarding a new cluster = point ArgoCD at the repo.

**For ShopEase:** roadmap item. Cluster bootstrap (ESO, LB Controller) stays in Terraform; app workloads move to ArgoCD.

---

### Q36. What's an Operator and when do you write one?
An **Operator** is a controller that watches a Custom Resource (CRD) and reconciles the world to match it. ESO is an Operator: you create an `ExternalSecret` CR; ESO calls AWS Secrets Manager and creates a K8s Secret.

You write your own when:
- You have a complex stateful app (Postgres, Kafka) and want declarative lifecycle.
- You're operationalizing internal platform primitives ("create a tenant" → spawn namespace + quotas + DB).

For 99% of teams, **consuming** operators (Prometheus Operator, ESO, cert-manager) is the right answer. Writing one is a senior platform job.

---

### Q37. How do you observe a cluster — metrics, logs, traces?
- **Metrics**: Prometheus scrapes `/metrics` endpoints (kubelet, kube-state-metrics, app's Spring Actuator). Grafana dashboards on top.
- **Logs**: structured JSON logs from apps → Fluent Bit/Vector DaemonSet → Loki or CloudWatch Logs or Elasticsearch.
- **Traces**: OpenTelemetry SDK in app → OTel Collector → Jaeger/Tempo. Critical for understanding distributed call paths.
- **Events**: K8s events (`kubectl get events`) are gold for "why didn't this pod start?" — ship them to your logging stack.

**Golden signals** (SRE): Latency, Traffic, Errors, Saturation. Build dashboards/alerts around these per service.

ShopEase: Spring Actuator endpoints already exposed (`/actuator/prometheus`). Stack itself is a roadmap item.

---

### Q38. How do you handle multi-environment (dev/staging/prod)?
Two parts:
1. **Separate clusters per env** (strong isolation, costs more) or **separate namespaces** (cheaper, weaker isolation). For prod, prefer separate cluster.
2. **Manifest reuse via Kustomize or Helm**:
   - **Kustomize**: `base/` + `overlays/dev`, `overlays/staging`, `overlays/prod`. Overlays patch what differs (replicas, image tags, resource limits, secrets reference). Pure YAML, no templating.
   - **Helm**: templated charts + values files per env. More powerful, more complexity.

**Same image promoted across envs**, only config differs. If staging passes, the literal image SHA goes to prod.

---

### Q39. Walk me through your rolling update settings — `maxUnavailable`, `maxSurge`, `minReadySeconds`.
- **maxUnavailable** — how many pods below desired count are tolerated during rollout. `0` means strict zero-downtime — never go below current replicas.
- **maxSurge** — how many pods above desired count are allowed temporarily. `1` means create one new pod at a time before killing an old one.
- **minReadySeconds** — after a pod is Ready, wait this long before considering it "stable" and proceeding. Catches "passes readiness but crashes 5 s later".

ShopEase uses `maxUnavailable: 0, maxSurge: 1, minReadySeconds: 10` — slow but ironclad.

**Trade-off:** large `maxSurge` = faster rollout but bigger blast radius if the new version is broken. For prod-critical, keep `maxSurge: 1` so a bad image only ever has one bad pod live at a time.

---

### Q40. If you had 30 minutes to harden a vanilla EKS cluster for production, what's on the list?
Priority order:
1. **PodSecurity admission** namespace label set to `restricted`.
2. **SecurityContext** on every Deployment: `runAsNonRoot`, drop capabilities, `readOnlyRootFilesystem`, `seccomp: RuntimeDefault`.
3. **Resource requests + limits** on every container.
4. **HPA + PDB** per workload.
5. **NetworkPolicy** default-deny in app namespaces, explicit allows.
6. **IRSA** for any pod needing AWS access. Never use node role for app workloads.
7. **Secrets** out of Git — ESO or Vault.
8. **etcd KMS encryption** at cluster create (EKS option).
9. **Audit logging** enabled, shipped to CloudWatch.
10. **Image scanning** — ECR scan-on-push, block deploys of critical CVEs.
11. **Private API endpoint** or restricted public CIDRs.
12. **Cluster Autoscaler / Karpenter** so HPA actually has nodes to scale into.

This is roughly what ShopEase has today minus NetworkPolicy and full audit pipeline.
