# Kubernetes — Scenario-Based Interview Questions & Answers

> Format: each scenario gives the **prompt** as an interviewer would say it, an **analysis** of what they're actually probing, and a **structured answer** you can deliver in 60–120 seconds.
> Project-grounded: answers reference ShopEase's actual setup wherever possible.

---

## Scenario 1 — "A pod is in `CrashLoopBackOff`. Walk me through your debugging."

**What they're probing:** Methodical triage, knowledge of kubectl primitives, log-vs-event reasoning.

**Answer:**
1. **Confirm symptom** — `kubectl get pod <pod> -n <ns>` → note `RESTARTS` count and `STATUS`.
2. **Recent events first** — `kubectl describe pod <pod>`. The `Events:` section at the bottom tells me almost every common cause: image pull failure, OOMKilled, failed probe, scheduling failure, volume mount error.
3. **Container logs** — `kubectl logs <pod> -c <container> --previous` (the `--previous` flag is critical because the current container has already died; you need the previous incarnation's logs).
4. **Differentiate the class of failure:**
   - *Application exit* → logs show stack trace → fix code / config.
   - *Liveness probe killing it* → events show `Liveness probe failed`; probe is too aggressive or app slow to warm → add/tune `startupProbe`.
   - *OOMKilled* → `kubectl get pod -o yaml | grep -A3 lastState` → reason `OOMKilled` → raise memory limit or fix leak.
   - *Missing config* → app logs say "env var FOO not set" → check ConfigMap/Secret reference.
5. **For ShopEase specifically:** I separated startup, liveness, readiness probes precisely to avoid the most common CrashLoopBackOff cause — Spring Boot's 30–60 s warmup tripping a liveness probe.

---

## Scenario 2 — "A Deployment is updated but pods stay on the old version. Why?"

**What they're probing:** Understanding of replica controllers, image caching, label selectors.

**Answer:**
- First check `kubectl rollout status deployment/<name>` — is it stuck or did it report success?
- If success but pods are old, the most common cause is **`imagePullPolicy: IfNotPresent` combined with a reused tag** like `:latest`. The node already has that tag cached so it never pulls the new image.
- ShopEase prevents this two ways: (1) **immutable ECR repository** so a tag can't be overwritten, (2) **versioned image tags** (`1.1.0`, `1.2.0`) so each release uses a fresh tag.
- Other culprits: selector mismatch (Deployment owns a different label than expected), HPA fighting `replicas`, paused rollout (`kubectl rollout pause`), or PDB blocking eviction.
- Diagnosis: `kubectl describe deployment` → look at `OldReplicaSets` and `Conditions`.

---

## Scenario 3 — "How would you do a zero-downtime deployment for a stateless service?"

**What they're probing:** Rolling update mechanics, probe wiring, PDB usage.

**Answer:** Four pieces in concert:

1. **RollingUpdate strategy** with `maxUnavailable: 0, maxSurge: 1` — never drop below desired count.
2. **Readiness probe** that *only* returns Ready after the app can serve traffic. While it returns 503, the Service stops sending traffic to that pod. New pods get traffic only when truly ready.
3. **`preStop` hook + `terminationGracePeriodSeconds`** — when a pod is killed, K8s removes it from the Service endpoints *and* sends SIGTERM. The grace period (ShopEase uses 60 s) lets in-flight requests drain. Spring Boot's `server.shutdown=graceful` handles this.
4. **PodDisruptionBudget** `minAvailable: 1` — prevents node drains during cluster ops from taking the whole service down.

End result: rollout swaps pods one at a time, traffic only goes to fully warmed pods, kill signal triggers graceful drain. Zero 5xx during deploy.

---

## Scenario 4 — "Pod can't reach a Service. How do you debug?"

**What they're probing:** DNS, kube-proxy, network model, NetworkPolicy awareness.

**Answer:** Walk the stack bottom-up.

1. **Resolve DNS first** — `kubectl exec <pod> -- nslookup <svc-name>.<ns>.svc.cluster.local`. If DNS fails → CoreDNS issue.
2. **Check the Service exists** — `kubectl get svc -n <ns>`, confirm correct `selector` matches Pod labels (`kubectl get pod -l <selector>` should return them).
3. **Endpoints populated?** — `kubectl get endpoints <svc>`. Empty endpoints = no Ready pods, label mismatch, or readiness probe failing.
4. **Reach the Pod IP directly** — bypass the Service: `kubectl exec <pod> -- curl <pod-ip>:<port>`. If this works, problem is at Service/kube-proxy layer. If it fails, problem is at the pod or NetworkPolicy.
5. **NetworkPolicy** — if one exists in the target namespace, default-deny may block your source. Check `kubectl get networkpolicy -n <ns>`.
6. **Security groups (EKS-specific)** — if pods land on different nodes, node SGs must allow the pod CIDR. VPC CNI usually handles this.

For ShopEase today there's no NetworkPolicy yet — that's a known gap on my hardening roadmap.

---

## Scenario 5 — "Your cluster is hitting node CPU pressure. What do you do?"

**What they're probing:** Resource management, autoscaling levels, eviction behaviour.

**Answer:** Three time-horizons:

- **Immediate:** Check who's burning CPU — `kubectl top pods --all-namespaces --sort-by=cpu`. Identify outliers. If it's a runaway pod, restart it; if a noisy neighbour, cordon and migrate.
- **Short-term:** Ensure pods have correct **requests** (scheduler bin-packs by requests) and **limits** (caps burst). Wrong requests = noisy neighbours starve well-behaved pods.
- **Structural:** Two scaling levers:
  - **HPA** — scale pod count on CPU/memory or custom metrics. ShopEase has this 2→6 per service.
  - **Cluster Autoscaler / Karpenter** — scale node count. Without this, HPA hits `Pending` pods and stalls. ShopEase node group has `min=2, max=4` autoscaling.
- **Long-term:** Look at request-vs-actual ratio in Prometheus — chronic over-request wastes money, chronic under-request causes evictions.

---

## Scenario 6 — "A secret was leaked in Git. What's the remediation?"

**What they're probing:** Security incident response, Git internals.

**Answer:**
1. **Rotate the value immediately** — assume it's compromised. In ShopEase, that means updating the value in AWS Secrets Manager → ESO syncs within 1 h → `kubectl rollout restart deployment` to pick it up.
2. **Revoke any derived credentials** — if it was an AWS access key, deactivate in IAM. If a JWT signing key, force re-login for all users.
3. **Scrub Git history** — `git filter-repo --invert-paths --path <file>` (or BFG). Force-push to all branches. Notify team to re-clone.
4. **Audit logs** — CloudTrail for AWS API calls, application logs for suspicious sessions during the exposure window.
5. **Prevent recurrence** — `.gitignore` patterns blocking `secret.yaml`, pre-commit hook with `gitleaks` or `trufflehog`, mandatory PR review on `deployment-kubernetes/`.
6. **In ShopEase**, we did exactly this when migrating off plaintext secrets: ESO is now the only path, `secret.yaml` files were deleted, `.gitignore` blocks the filename pattern, and rotation no longer requires a Git change.

---

## Scenario 7 — "A pod needs to call an AWS API. How do you grant access?"

**What they're probing:** IRSA understanding (huge differentiator).

**Answer:** Three wrong answers and one right one:

- ❌ **Bake AWS access keys into the image** — credentials in source, no rotation, exfiltration via `docker history`.
- ❌ **Mount keys via Secret** — better but still static, manual rotation, blast radius is the whole node.
- ❌ **Use the node IAM role** — every pod on that node inherits the role; violates least privilege at pod level.
- ✅ **IRSA — IAM Roles for Service Accounts.**

**How IRSA works:**
1. EKS cluster has an OIDC identity provider registered with IAM.
2. You create an IAM role whose trust policy says *"trust tokens from this OIDC issuer where `sub == system:serviceaccount:<ns>:<sa-name>`"*.
3. The K8s ServiceAccount carries the annotation `eks.amazonaws.com/role-arn: <role-arn>`.
4. The EKS Pod Identity Webhook injects `AWS_ROLE_ARN` + `AWS_WEB_IDENTITY_TOKEN_FILE` env vars + a projected token volume.
5. AWS SDK in the pod calls `sts:AssumeRoleWithWebIdentity` automatically. Credentials are short-lived and rotated.

**ShopEase example:** auth-service publishes to SNS on signup → has SA `auth-service-sa` → annotated with an IAM role allowing `sns:Publish` only on the signup topic ARN. Other services have bare SAs with no AWS permissions.

---

## Scenario 8 — "A Helm upgrade failed halfway. The cluster is in a weird state. Recover."

**What they're probing:** Helm internals, release history, atomic patterns.

**Answer:**
1. **Inspect history** — `helm history <release> -n <ns>` shows all revisions and their status.
2. **Roll back** — `helm rollback <release> <previous-revision> -n <ns>`. Helm re-applies the manifests from that revision.
3. **If state is corrupt** (release stuck in `pending-upgrade`): `helm rollback` may fail. Use `helm rollback --force` or in worst case `kubectl edit secret sh.helm.release.v1.<release>.v<rev>` to fix the stored state.
4. **Prevent next time:** install/upgrade with `--atomic --wait --timeout 10m` — Helm rolls back automatically on failure instead of leaving half-applied state.
5. **ShopEase pattern:** our ESO `helm_release` Terraform resource sets `atomic = true, wait = true, timeout = 600` for exactly this reason.

---

## Scenario 9 — "Your CI pipeline pushes images on every commit. How do you prevent prod from getting an untested image?"

**What they're probing:** Promotion strategy, environment isolation, image tagging discipline.

**Answer:**
- **Separate registries or repository paths** per environment (`shopease-dev/auth-service` vs `shopease-prod/auth-service`).
- **Immutable tags** at the registry level so a `:1.2.0` tag in prod can't be silently replaced.
- **Semantic version tags for prod**, commit-SHA tags for dev/staging. Never `:latest` anywhere.
- **Promotion = retag + push to prod repo** after staging validation (or replicate the digest, not the tag).
- **GitOps boundary:** dev environment auto-deploys on push; staging/prod require a PR to a separate manifests repo, gated by review.
- **ShopEase today:** ECR repos set to `IMMUTABLE`. Tags are versioned. Promotion is still manual — that's a CI/CD gap on my roadmap.

---

## Scenario 10 — "How do you handle a noisy pod that's exhausting node memory and OOMKilling its neighbours?"

**What they're probing:** Resource model, QoS classes, eviction.

**Answer:**
- **Set memory `limits` on every pod** — without a limit, a pod can grow until the kernel OOMKills *something* on the node; that something is whichever pod is over its request the most. With a limit, only the offender dies.
- **Understand QoS classes:**
  - `Guaranteed` (requests == limits) — last to be evicted.
  - `Burstable` (requests < limits) — middle priority.
  - `BestEffort` (no requests/limits) — first to die under pressure.
- **For critical workloads** like ShopEase's auth-service, I use `Guaranteed` QoS plus `PriorityClass: shopease-critical` so even under pressure the scheduler protects them.
- **Long-term:** Prometheus alerts on `container_memory_working_set_bytes` near the limit, leading indicator for OOMs.

---

## Scenario 11 — "How do you store and inject config that differs per environment?"

**What they're probing:** 12-factor, ConfigMap vs Secret, overlay strategies.

**Answer:**
- **Non-sensitive per-env values** → ConfigMap (`SPRING_PROFILES_ACTIVE`, `LOG_LEVEL`, service URLs).
- **Sensitive per-env values** → Secret backed by AWS Secrets Manager via ESO. Different secrets per environment (`shopease/dev/...` vs `shopease/prod/...`).
- **Per-env image tags / replica counts** → today I duplicate manifests in `environments/development` style; the proper enterprise pattern is **Kustomize overlays**: a `base/` with common manifests + `overlays/dev/` and `overlays/prod/` that patch what's different (replicas, image tags, resource limits).
- **Strict rule:** the same image runs in every environment, only config changes. Anything that requires a different image is a smell.

---

## Scenario 12 — "You need to drain a node for maintenance. Walk me through it."

**What they're probing:** Day-2 ops, PDB awareness, graceful eviction.

**Answer:**
1. **Cordon** — `kubectl cordon <node>`. Marks node unschedulable so new pods don't land there.
2. **Drain** — `kubectl drain <node> --ignore-daemonsets --delete-emptydir-data`. Evicts pods one by one. K8s respects **PodDisruptionBudget** during eviction — if a service has `minAvailable: 1` and only 1 pod, drain will pause until another pod is up elsewhere.
3. **What to watch for:**
   - DaemonSets need `--ignore-daemonsets` (they're tied to the node lifecycle, will come back when the node returns).
   - `emptyDir` data is lost — flag must be explicit so you know.
   - Pods without a PDB and without higher-level controllers (bare pods) get evicted with no replacement.
4. **After maintenance:** `kubectl uncordon <node>` returns it to the scheduler pool.
5. **ShopEase**: every Deployment has a PDB, so a node drain is safe — services stay available throughout.

---

## Scenario 13 — "Describe the request flow from a user clicking 'Add to cart' to the data being persisted."

**What they're probing:** End-to-end mental model.

**Answer:** (use ShopEase's stack)

1. Browser → CloudFront edge → cached SPA loads.
2. SPA makes `POST /api/cart/items` → CloudFront forwards to the AWS ALB.
3. ALB Ingress matches the `/api/cart/*` path → routes to `cart-service` Service (ClusterIP).
4. kube-proxy DNATs the Service IP to one of the Ready pod IPs (round-robin among endpoints).
5. The pod's Spring Boot app validates the JWT (using `APP_JWT_SECRET` from the ESO-managed K8s Secret, originally from AWS Secrets Manager).
6. Cart service writes to Aurora MySQL over the private subnet (DB credentials also from the K8s Secret).
7. Aurora replicates to read replicas in other AZs.
8. Response flows back up the same path.

**Failure-handling I've built in:** if the cart pod is mid-shutdown, the readiness probe flips to NotReady, kube-proxy removes it from endpoints, the next request goes to a sibling pod — user sees no error.

---

## Scenario 14 — "How would you migrate from secrets-in-Git to Secrets Manager without downtime?"

**What they're probing:** Real migration thinking. (This is exactly what you did in ShopEase.)

**Answer:** Step-by-step:

1. **Stand up the new path in parallel** without touching apps:
   - Create AWS Secrets Manager entries, populate from current values.
   - Provision a CMK to encrypt them.
   - Install ESO via Helm, set up IRSA, create `ClusterSecretStore`.
2. **Validate** ESO can read by creating `ExternalSecret` resources that write to a *different* K8s Secret name (e.g., `auth-service-secret-eso`). Inspect contents, compare to existing.
3. **Switch over carefully:**
   - **Option A (safer for prod):** edit Deployments to use the new Secret name, rollout, verify, delete old Secret + manifests.
   - **Option B (faster for dev):** delete the old K8s Secret, apply ExternalSecret with the same name (ESO recreates it as `creationPolicy: Owner`), rollout. Apps need no manifest change.
4. **Clean up source control:** remove plaintext YAMLs, add `.gitignore` patterns.
5. **Rotate the values** — assume they leaked via Git history. Rotation now flows through Secrets Manager only.
6. **In ShopEase I used Option B** because it's a dev environment and the swap was reversible by re-applying the old YAML if anything broke.

---

## Scenario 15 — "Auth-service pod logs show a 403 from AWS when calling SNS. What's wrong?"

**What they're probing:** IRSA debugging.

**Answer:** Most likely one of these, in order:
1. **SA annotation missing or wrong** — `kubectl get sa auth-service-sa -n shopease-webapp-development -o yaml`; the `eks.amazonaws.com/role-arn` annotation must exist and match the IAM role.
2. **Pod didn't pick up the SA** — `kubectl get pod <pod> -o yaml | grep serviceAccountName`; ensure the Deployment specifies `serviceAccountName: auth-service-sa`. If left empty, the pod uses the `default` SA.
3. **Pod started before the annotation existed** — IRSA env-var injection happens at pod admission. An annotation added later requires a pod restart.
4. **IAM trust policy mismatch** — the role's trust policy must match exactly `system:serviceaccount:<namespace>:<sa-name>`. A typo here causes silent `AccessDenied`. Check in IAM console or `aws iam get-role`.
5. **Policy doesn't grant the action** — `sns:Publish` must be allowed on the specific topic ARN. CloudTrail will show the denied call with the role ARN — confirm role and resource.
6. **OIDC provider mismatch** — rare, but if the cluster was recreated and the OIDC provider regenerated, old roles trust the wrong issuer.
