# 🎙️ ShopEase — End-to-End Architecture Walkthrough
*(Interview narrative for a 3-YOE DevOps Engineer)*

---

## 🪜 How to use this document

This is structured as **what an interviewer wants to hear**: business context → architecture → tech choices → flow → trade-offs → reliability → cost → improvements. Speak it in this order and you'll cover everything a senior interviewer probes for.

---

# 1. Project Pitch (30-second elevator)

> "ShopEase is a microservices-based e-commerce platform I built end-to-end on AWS. The frontend is a React SPA hosted on S3 and served via CloudFront. The backend is four Spring Boot microservices — `auth`, `product`, `cart`, `order` — running on Amazon EKS, fronted by an internet-facing ALB. Persistence is on Aurora PostgreSQL Serverless v2 in private subnets. Asynchronous welcome emails are handled by SNS → SQS → Lambda → SES. The entire infrastructure is codified in Terraform, organized into reusable modules with environment overlays for dev and production. Kubernetes manifests follow per-service folder structure with a shared ALB via the AWS Load Balancer Controller's IngressGroup feature."

---

# 2. High-Level Architecture

```
                                🌍 INTERNET
                                     │
                                     ▼
                          ┌─────────────────────┐
                          │     CLOUDFRONT      │   ← Single entry point (HTTPS)
                          │   (Edge / CDN)      │     • TLS termination
                          └────┬───────────┬────┘     • Path-based routing
                               │           │          • DDoS protection (Shield Std)
                Path: /        │           │   Path: /api/*
                (static)       │           │   (dynamic)
                               ▼           ▼
                       ┌──────────┐   ┌──────────┐
                       │    S3    │   │   ALB    │   ← Layer 7 LB
                       │  Bucket  │   │ (public) │
                       │  (React) │   └────┬─────┘
                       └──────────┘        │
                       (OAC-locked)        │ HTTP :80 (intra-AWS)
                                           ▼
                                ╔══════════════════════╗
                                ║   AMAZON EKS         ║
                                ║   (3 AZs, private)   ║
                                ║                      ║
                                ║   ┌──────────────┐   ║
                                ║   │ AWS LB Ctrl  │   ║   ← Reads Ingress objects,
                                ║   │ (IRSA pod)   │   ║     reconciles ALB state
                                ║   └──────┬───────┘   ║
                                ║          │           ║
                                ║   ┌──────▼───────┐   ║
                                ║   │  4 Ingress   │   ║   ← group.name shares one ALB
                                ║   │  (per svc)   │   ║
                                ║   └──┬─┬─┬─┬─────┘   ║
                                ║      │ │ │ │         ║
                                ║      ▼ ▼ ▼ ▼         ║
                                ║   ┌──┐┌──┐┌──┐┌──┐   ║   Services (ClusterIP)
                                ║   │S1││S2││S3││S4│   ║
                                ║   └─┬┘└─┬┘└─┬┘└─┬┘   ║
                                ║     │   │   │   │    ║
                                ║   ┌─▼─┐ ▼ ┌─▼─┐ ▼    ║   Pods (2 replicas each)
                                ║   │P1 │P2│P3 │P4│    ║   • auth :8080
                                ║   └─┬─┘ │ └─┬─┘ │    ║   • product :8081
                                ║     │   │   │   │    ║   • cart :8082
                                ╚═════╪═══╪═══╪═══╪════╝   • order :8083
                                      │   │   │   │
                                      ▼   ▼   ▼   ▼
                              ┌─────────────────────────┐
                              │  Aurora PostgreSQL v2   │  ← Private subnets only
                              │  Serverless (1-2 ACU)   │     SG-locked to EKS nodes
                              └─────────────────────────┘

         (Async path for welcome email on signup)
         auth-svc ──► SNS ──► SQS ──► Lambda ──► SES ──► User inbox
```

---

# 3. Technology Choices & Justification

| Layer | Choice | Why this over alternatives |
|---|---|---|
| **CDN** | CloudFront | AWS-native, free Shield Standard, OAC for S3, supports multiple origins (key for our `/api/*` pattern) |
| **Static hosting** | S3 + OAC | Cheaper than serving from EKS, edge-cached, durable (11 nines), bucket stays private |
| **Compute** | EKS (managed) | Industry standard, IRSA for fine-grained IAM, ecosystem (Helm, ArgoCD, Karpenter ready). ECS would lock us in; self-managed K8s is ops overhead. |
| **Ingress** | AWS Load Balancer Controller | Translates K8s Ingress → ALB. Native to AWS, supports IngressGroup (cost), IRSA. Nginx Ingress would need its own NLB and more ops. |
| **Database** | Aurora Serverless v2 PostgreSQL | Auto-scales 1–2 ACU in dev (cheap when idle), HA across AZs, point-in-time recovery. Cheaper than provisioned Aurora at low traffic. |
| **Async messaging** | SNS → SQS → Lambda | Decouples signup from email sending. Retries via SQS DLQ. Scales to zero. |
| **IaC** | Terraform | Multi-cloud capable, mature module ecosystem, plan/apply review workflow. CloudFormation locks us to AWS. |
| **Container registry** | ECR (one repo per service) | IAM-integrated, lifecycle policies for image GC, scan on push. |
| **Networking** | VPC, 3 AZs, NAT per AZ | HA: failure of one AZ doesn't kill private-subnet egress. Cross-AZ NAT data charges avoided. |

---

# 4. Network Topology

```
VPC: 10.0.0.0/16
│
├── ap-south-1a
│   ├── Public  10.0.1.0/24    ← NAT-GW-1a + ALB ENI
│   └── Private 10.0.10.0/24   ← EKS nodes + RDS instances
│
├── ap-south-1b
│   ├── Public  10.0.2.0/24    ← NAT-GW-1b + ALB ENI
│   └── Private 10.0.20.0/24   ← EKS nodes + RDS instances
│
└── ap-south-1c
    ├── Public  10.0.3.0/24    ← NAT-GW-1c + ALB ENI
    └── Private 10.0.30.0/24   ← EKS nodes + RDS instances
```

**Subnet tags** (critical for ALB controller auto-discovery):

| Subnet | Tag | Purpose |
|---|---|---|
| Public | `kubernetes.io/role/elb=1` | Eligible for internet-facing ALBs |
| Private | `kubernetes.io/role/internal-elb=1` | Eligible for internal ALBs/NLBs |
| Both | `kubernetes.io/cluster/shopease-webapp-dev=shared` | Tells controller which cluster owns these subnets |

**Routing:**
- Public subnet → IGW (default route to internet)
- Private subnet → NAT GW in the *same AZ* (so pulling from ECR/internet doesn't pay cross-AZ)
- All subnets → VPC local routes for intra-VPC traffic

---

# 5. Kubernetes Layer — Deep Dive

## 5.1 Workload definition (each microservice)

Three YAMLs per service in its own folder:

```
deployment-kubernetes/auth-service/
├── deployment.yaml   ← Pod spec, replicas, probes, resources
├── service.yaml      ← ClusterIP Service (port 80 → targetPort 8080)
└── ingress.yaml      ← Routes /api/auth/* → this service
```

## 5.2 Why per-service YAMLs

| Pattern | Pros | Cons |
|---|---|---|
| One giant manifest | Simple to apply | Merge conflicts between teams, blast radius |
| Per-service folder ⭐ | Team ownership, GitOps friendly, matches Helm chart layout | Slight duplication |

We chose **per-service** because it maps naturally to:
- Future Helm/Kustomize migration (each folder becomes a chart)
- Per-service ArgoCD `Application` objects
- Independent rollouts

## 5.3 Probes (production hygiene)

```yaml
startupProbe:    failureThreshold: 30, periodSeconds: 5  # 150s grace for slow Spring Boot
readinessProbe:  initialDelaySeconds: 10, periodSeconds: 30  # When to add to Service endpoints
livenessProbe:   initialDelaySeconds: 60, periodSeconds: 30  # When to restart pod
```

- **Startup probe** is critical for JVMs — they take 30–60 s to warm up. Without it, liveness would kill the pod before it's ready.
- **Readiness** controls Service endpoints — if it fails, pod is removed from rotation but not killed (graceful).
- **Liveness** triggers a kubelet restart if the app deadlocks.

## 5.4 IngressGroup pattern (the **most-asked** interview point on this stack)

**Problem:** Each Ingress = one ALB by default ($16/mo × 4 services = $64/mo just for LBs).

**Solution:** AWS Load Balancer Controller's `group.name` annotation.

```yaml
metadata:
  annotations:
    alb.ingress.kubernetes.io/group.name: shopease-webapp   # ← merges into single ALB
    alb.ingress.kubernetes.io/group.order: "10"             # ← rule priority
    alb.ingress.kubernetes.io/load-balancer-name: shopease-webapp-alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTP": 80}]'
    alb.ingress.kubernetes.io/healthcheck-path: /api/auth/health   # per-service
```

**Result:** 4 separate Ingress objects → **1 shared ALB** with 4 listener rules → 4 target groups, each with its own health check.

**`target-type: ip`**: ALB sends traffic directly to pod IPs (not to the Service ClusterIP). Faster path, required for Fargate, plays well with the AWS VPC CNI.

## 5.5 How the controller actually works

```
1. You: kubectl apply -f auth-service/ingress.yaml
2. K8s API stores the Ingress object
3. AWS LB Controller (running in kube-system, IRSA-authenticated):
   a. Lists all Ingresses with group.name=shopease-webapp
   b. Computes desired ALB state (rules, target groups)
   c. Calls AWS APIs (CreateLoadBalancer, CreateRule, RegisterTargets)
4. Controller watches K8s endpoints — when pods come/go,
   it calls RegisterTargets/DeregisterTargets continuously
5. ALB DNS name is written back to Ingress.status.loadBalancer.ingress[0].hostname
```

This is **the** killer feature for AWS-native EKS. Mention IRSA explicitly — it's how the controller calls AWS APIs without static credentials.

---

# 6. Frontend ↔ Backend Integration

## 6.1 The challenge

Frontend is on `cloudfront.net`, backend is on `*.elb.amazonaws.com`. Naive solution = call ALB directly from React. **Problems:**
- ❌ CORS preflights on every POST/PUT
- ❌ Two domains in browser network tab (ugly, hard to debug)
- ❌ ALB DNS leaks to client → no abstraction layer
- ❌ ALB DNS would need to be hardcoded in the JS bundle

## 6.2 The pattern: same-origin via CloudFront multi-origin

CloudFront has **two origins**, chosen by **path pattern**:

| Path pattern | Origin | Cache policy | Origin request policy |
|---|---|---|---|
| `default (*)` | S3 (React) | `Managed-CachingOptimized` | (default) |
| `/api/*` | ALB | **`Managed-CachingDisabled`** | **`Managed-AllViewer`** |

**Why these specific managed policies:**
- `CachingDisabled` for `/api/*`: API responses are user-specific (cart, orders, JWT). Caching = security incident.
- `AllViewer` for origin requests: forwards `Authorization`, `Cookie`, `Content-Type`, query strings — everything the backend needs.

## 6.3 Frontend code change (the "click")

Old:
```javascript
const API_BASE = `http://${window.location.hostname}:8080/api`  // ❌ port-based
```

New:
```javascript
const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api'    // ✅ relative
```

This single line change makes the same code work in:
- **Local dev** (Vite proxy forwards `/api/*` to Spring Boot on `localhost:808X`)
- **Production** (CloudFront's `/api/*` behavior forwards to ALB)

## 6.4 Vite dev proxy

```javascript
proxy: {
  '/api/auth':     { target: 'http://localhost:8080', changeOrigin: true },
  '/api/products': { target: 'http://localhost:8081', changeOrigin: true },
  '/api/cart':     { target: 'http://localhost:8082', changeOrigin: true },
  '/api/orders':   { target: 'http://localhost:8083', changeOrigin: true },
}
```

Vite acts as the local equivalent of CloudFront's `/api/*` behavior — **path-based fan-out to local services**. Same mental model, different implementation.

---

# 7. End-to-End Request Trace (the *flagship* answer)

## Scenario: Logged-in user adds an item to cart

```
[1] User clicks "Add to Cart" on /products/42
    │
[2] React component fires:
    cartApi.addToCart({ productId: 42, quantity: 1 })
    │
[3] cart.js builds request:
    fetch('/api/cart/items', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': 'Bearer eyJhbGc...'   ← read from localStorage
      },
      body: JSON.stringify({ productId: 42, quantity: 1 })
    })
    │
[4] Browser resolves /api/cart/items relative to current origin:
    https://d1obwmioh77a8o.cloudfront.net/api/cart/items
    │
[5] DNS → CloudFront edge (closest POP, e.g., Mumbai)
    │
[6] CloudFront pattern-match:
    /api/* → ALB origin (NOT cached, ALL methods, AllViewer headers/cookies)
    │
[7] CloudFront → ALB DNS (over AWS backbone, HTTP :80, persistent connection pool)
    │
[8] ALB listener (HTTP :80) evaluates rules in priority order:
    Rule 30 (priority 30): /api/cart/* → cart-service-tg ✅
    │
[9] ALB picks healthy target from cart-service-tg:
    Pod 10.0.20.45:8082 (target-type: ip — direct to pod ENI)
    │
[10] AWS VPC CNI routes packet to pod IP within EKS data plane
    │
[11] Spring Boot cart-service receives:
     POST /api/cart/items
     - JwtFilter validates Authorization header (RS256 signature, exp claim)
     - extracts userId from token
     - CartService.addItem(userId, productId, quantity)
     - Hibernate persists to RDS Aurora (private subnet, SG locked)
     - returns 200 with updated cart JSON
    │
[12] Response travels back: pod → ALB → CloudFront → browser
    │
[13] React receives JSON, updates CartContext, re-renders Navbar badge
```

**Latency budget** (rough, p50):
- Browser → CF edge: 10–30 ms
- CF → ALB (intra-region): 2–5 ms
- ALB → pod: <1 ms
- App + DB query: 30–80 ms
- Total: **~60–120 ms** end-to-end

---

# 8. Auth Flow

```
1. User submits /api/auth/signup
   → auth-service inserts user row in RDS
   → publishes message to SNS topic "signup-topic"
   → returns 201

2. SNS fans out to SQS subscriber "signup-email-queue"

3. Lambda "welcome-email" polls SQS (event source mapping)
   → reads user email from message
   → SES.SendEmail() → user inbox
   → on failure, message returns to queue; after N retries → DLQ

4. User submits /api/auth/login
   → auth-service verifies bcrypt password
   → issues JWT (signed with private key)
   → returns { token, user }

5. Frontend stores token in localStorage
   → All subsequent /api/cart, /api/orders calls include
     Authorization: Bearer <token>
   → Each microservice has a JwtFilter that:
     - validates signature (with public key / shared secret)
     - extracts userId
     - rejects 401 if invalid
```

**Why decoupled email via SNS→SQS→Lambda?**
- Signup doesn't wait for email sending (faster API)
- Email failures (SES throttling) don't fail signup
- Built-in retry + DLQ — no custom retry logic in app code
- Lambda scales to zero — we don't pay for an idle email worker

---

# 9. Security Posture

| Control | Implementation |
|---|---|
| **TLS in transit (public)** | CloudFront default cert, redirect HTTP→HTTPS |
| **S3 access** | Bucket fully private, OAC-only access from CloudFront, IAM bucket policy |
| **DB access** | RDS in private subnets, SG ingress only from EKS node SG, no public endpoint |
| **Pod IAM** | IRSA — each pod assumes its own role (no static AWS keys) |
| **Secrets** | DB password via Terraform sensitive var → planned to move to Secrets Manager + External Secrets Operator |
| **Image security** | ECR scan-on-push, lifecycle policy (max 10 images / repo) |
| **Network segmentation** | Workloads in private subnets, no public IPs on pods |
| **DDoS** | CloudFront + AWS Shield Standard (free) |
| **DNS-level protection** | Single public surface = CloudFront; ALB not announced via DNS to clients |

**Gaps to address next:** WAF on CloudFront, NetworkPolicies in K8s, secrets in AWS Secrets Manager, mTLS between services (service mesh).

---

# 10. Reliability & Scalability

## High availability
- **3 AZs** for EKS nodes, ALB, RDS, NAT
- **Replicas: 2** per microservice (minimum)
- **Aurora multi-AZ** failover (~30 s RTO)
- **Pod anti-affinity** (planned) to spread replicas across nodes

## Auto-scaling (current vs. planned)

| Layer | Today | Production-grade |
|---|---|---|
| Pods | Static 2 replicas | HPA on CPU + custom metrics (RPS) |
| Nodes | EKS managed node group, 3-6 nodes | Karpenter for spot + scheduled scaling |
| DB | Aurora Serverless v2, 1–2 ACU | Same, but tuned ACU range per env |

## Failure modes

| Failure | Effect | Mitigation |
|---|---|---|
| AZ outage | 1/3 pods unreachable | Other AZs serve; ALB removes targets |
| Node failure | Pods rescheduled | Replicas ensure ≥1 always healthy |
| Pod crash | Restarted by kubelet | Liveness probe + replicas |
| RDS primary fails | Aurora promotes replica | App reconnects on next query |
| CloudFront origin (ALB) down | API down, frontend still loads | Custom error page (planned) |

---

# 11. Cost Story

| Resource | Approx. monthly (dev) | Optimization applied |
|---|---|---|
| EKS control plane | $73 | Required, no alternative |
| 3× t3.medium nodes | ~$90 | Could use spot for non-prod |
| 3× NAT GW | ~$100 | Per-AZ avoids cross-AZ data fees |
| **1 ALB (shared)** | ~$16 | **IngressGroup saved 3 × $16 = $48/mo** |
| Aurora Serverless v2 (1 ACU) | ~$45 idle | Scales to 1 ACU when idle |
| CloudFront | <$1 dev | Pay-per-request, no fixed |
| S3 | <$1 | Tiny static bundle |
| Lambda + SNS + SQS + SES | <$1 | Pay-per-invocation |
| **Total** | **~$330/mo** | Could be $200 with single NAT, spot nodes |

---

# 12. CI/CD & Operational Story (current vs. target)

**Today (manual but repeatable):**
1. `terraform apply` from `environments/development/`
2. `mvn package` + `docker build` + `docker push <ECR>`
3. `kubectl apply -R -f deployment-kubernetes/`
4. `npm run build` + `aws s3 sync dist/ s3://...` + `aws cloudfront create-invalidation`

**Target (interview-grade answer):**
- **GitHub Actions** workflow per service:
  - On PR: lint, test, build, scan
  - On main: build image, push to ECR with `git-sha` tag, update K8s manifest tag, commit
- **ArgoCD** watching the manifests repo → auto-syncs to EKS
- **Terraform Cloud** or Atlantis for IaC PRs
- **Frontend**: GitHub Actions builds, syncs to S3, invalidates CloudFront
- **Image promotion**: dev images promoted to prod by re-tagging in ECR (no rebuild)

---

# 13. Observability Plan

**Today:** CloudWatch logs (default for EKS, ALB, Lambda, RDS).

**Production-grade roadmap:**
- **Metrics**: Prometheus (kube-prometheus-stack), Grafana dashboards per service
- **Logs**: Fluent Bit → CloudWatch / Loki, structured JSON logs from Spring Boot
- **Traces**: OpenTelemetry agent in each pod → AWS X-Ray (or Jaeger)
- **Alerts**: Alertmanager → PagerDuty for SLO burn rate (e.g., p99 > 500 ms for 5 m)
- **SLOs**:
  - 99.9% availability per service (43 m downtime/month)
  - p95 latency: 200 ms for reads, 500 ms for writes

---

# 14. Trade-offs To Call Out (interviewer bonus points)

1. **3 NAT Gateways** — chose HA over cost ($100/mo). For dev I'd ideally use 1 NAT.
2. **`target-type: ip`** — locks us deeper into AWS VPC CNI; can't use alternative CNIs like Calico without rework.
3. **JWT in localStorage** — vulnerable to XSS. Should move to httpOnly cookie + CSRF token for prod.
4. **No service mesh** — pragmatic at 4 services; would add Istio at ~10 services for mTLS, retries, traffic shifting.
5. **CloudFront `/api/*` change made via console** — currently undocumented in IaC. Tech debt; needs to be Terraformed before next env.
6. **Single tenant K8s namespace** — `shopease-webapp-development`. Multi-tenant would need NetworkPolicies + ResourceQuotas.

---

# 15. Likely Follow-up Questions & Crisp Answers

| Question | Answer |
|---|---|
| Why EKS, not ECS? | Kubernetes ecosystem (Helm, GitOps, Karpenter), portability, hiring pool. ECS is fine but locks us into AWS-only tooling. |
| How does the ALB know which pods to send traffic to? | Controller watches K8s Endpoints; `target-type: ip` registers pod ENIs directly via `RegisterTargets`. |
| Why CloudFront in front of an ALB? | Edge TLS, static-asset caching, single domain, AWS Shield, multi-origin (S3 + ALB) without CORS pain. |
| How do you handle DB schema changes? | Flyway migrations baked into Spring Boot startup; container won't go ready until migration succeeds. |
| What if you need 100 services tomorrow? | Move to Helm umbrella charts, Karpenter for compute scaling, Istio for cross-cutting concerns, ArgoCD for GitOps. |
| How do you secure secrets? | Today: Terraform sensitive var. Plan: External Secrets Operator pulling from AWS Secrets Manager, scoped via IRSA. |
| How do pods talk to AWS APIs? | IRSA — `aws_iam_openid_connect_provider` from EKS, ServiceAccount annotated with role ARN, AWS SDK auto-uses pod identity. |
| What about blue/green or canary deploys? | Today: K8s rolling update with `maxUnavailable: 1`. Target: Argo Rollouts with traffic shifting via ALB weighted target groups. |
| How would you migrate this to multi-region? | CloudFront stays. Add second EKS cluster + ALB in another region, Aurora Global DB, Route 53 latency-based routing. |
| Disaster recovery RPO/RTO? | RPO: ~5 min (Aurora continuous backup). RTO: ~30 min (re-apply Terraform + restore from snapshot). Want to get to RTO < 5 min via warm-standby in second region. |

---

# 16. The 60-Second Closing Statement

> "What I'm proudest of in this project is that the architecture isn't just functional — it's **layered with clear separation of concerns**. CloudFront owns the edge and routing; EKS owns compute orchestration; the AWS Load Balancer Controller bridges Kubernetes intent to AWS network resources via IngressGroup; Spring Boot owns business logic; Aurora owns persistence; SNS/SQS/Lambda handle async work. Every layer is independently testable, scalable, and replaceable. The frontend code change to use `/api` relative URLs is a one-liner, but it's the keystone that makes the whole same-origin pattern work — and same-origin is what eliminates an entire class of CORS, security, and operational headaches. I built the whole thing in Terraform modules with environment overlays, so spinning up a `production` environment is a config-file change, not a re-architecture."

---

# 🎯 Tips for delivering this in an interview

1. **Always start with the diagram** — draw it on the whiteboard. Interviewers grade clarity over completeness.
2. **Trace one request end-to-end** unprompted (Section 7) — shows you understand the system, not just memorized parts.
3. **Volunteer trade-offs** (Section 14) — senior engineers expect them. Hiding them looks junior.
4. **Quantify** — "$48/mo saved by IngressGroup", "150 ms p50", "~$330/mo". Numbers signal ownership.
5. **Use precise terminology**: IRSA, IngressGroup, OAC, target-type, listener rule, control plane, data plane.
6. **Have an opinion**: "I'd choose IngressGroup over per-service ALBs because…" — don't just describe.
