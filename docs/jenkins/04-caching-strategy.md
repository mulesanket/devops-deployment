# Build Caching Strategy — Decision Record

> **Status:** Implemented (commit `<TBD>`)
> **Decision:** S3 tarball cache, per-service Maven cache + shared Trivy DB cache
> **Author:** Sanket Mule
> **Date:** May 2026

---

## TL;DR

We cache Maven dependencies and the Trivy vulnerability database as **gzipped tarballs in S3**, restored at build start and re-uploaded at build end (only on success). This is the same pattern GitHub Actions `actions/cache`, GitLab CI `cache:`, and AWS CodeBuild use natively.

Expected savings: **~30–60 seconds per build** on the cache hit path.

---

## What we considered

Five options, ranked from least to most complex:

| # | Approach | Multi-AZ | Industry-standard | Complexity | Verdict |
|---|---|---|---|---|---|
| 1 | No cache (emptyDir every build) | ✅ | ✅ | None | Baseline. 4-min builds. |
| 2 | **S3 tarball cache** | ✅ | ✅ | Low (pipeline-only) | **Chosen** |
| 3 | EBS PVC (`ReadWriteOnce`, gp3) | ❌ AZ-pinned | ⚠️ Only for DBs, not caches | Medium (CSI driver, StorageClass, PVC) | Rejected |
| 4 | EFS PVC (`ReadWriteMany`) | ✅ | ⚠️ Not for `.m2` | Medium (EFS CSI, mount targets per AZ) | Rejected |
| 5 | `hostPath` on each node | ✅ (per node) | ❌ PSS-restricted, anti-pattern | High (node bootstrap, DaemonSet pre-warmer) | Rejected |
| 6 | Bazel/Gradle remote cache | ✅ | ✅ (at scale) | High (separate service to run + maintain) | Premature for 4 services |

---

## Why not EBS PVC (the obvious choice we rejected)

We installed the EBS CSI driver and gp3 StorageClass (still installed — useful for future stateful workloads like self-hosted observability stores). We did **not** use them for the build cache because:

1. **AZ pinning.** EBS volumes live in one AZ. Once provisioned, every pod that mounts the PVC is forced to schedule in that AZ. If that AZ goes down or has no capacity, builds queue indefinitely. This is acceptable for caches in theory but unnecessary friction in practice.
2. **`ReadWriteOnce` blocks parallel builds.** Only one node can mount the volume at a time. If two services try to build simultaneously and land on different nodes, one waits.
3. **Cache pollution risk.** Sharing a single `.m2/repository` across services means transitive dependency version conflicts can cross-contaminate. We'd need one PVC per service → multiplies orphan EBS cost.
4. **EBS attach latency.** 10–30s on first attach per node. Eats half the savings.
5. **Cache invalidation is manual.** Old artifacts sit in `.m2` forever. S3 cache keys can include lockfile hashes for trivial invalidation.
6. **Orphan volumes cost money.** `reclaimPolicy: Retain` means stale volumes pile up. `Delete` means a stray `kubectl delete pvc` loses the cache.

EBS+PVC is the right tool for databases, queues, observability stores. Wrong tool for a stateless build cache.

---

## Why not EFS

EFS is `ReadWriteMany` and multi-AZ — solves the AZ pinning. But Maven's `.m2/repository` is **tens of thousands of tiny files** (every JAR, POM, signature, checksum is its own file). EFS pays 3–10× the per-metadata-op latency of EBS gp3. For `mvn verify`, this often makes EFS-cached builds *slower* than no cache at all. Documented in multiple production benchmarks.

EFS for Trivy DB (a small number of large BoltDB files) would work. But running two storage paradigms in one pipeline for marginal benefit isn't worth it.

---

## Why not `hostPath`

- Pod Security Standards `restricted` profile **prohibits** hostPath. Security review would reject.
- Cache lost on node replacement (Karpenter, ASG rotation, AMI upgrade, spot reclaim — all routine).
- Concurrent pods on the same node corrupt the cache (Maven lock files don't cross processes).
- No central observability — can't see what's cached where via CloudWatch / S3 Inventory.

---

## What S3 tarball caching looks like in our pipeline

### Cache layout in S3

```
s3://shopease-webapp-development-ci-artifacts/_caches/
  ├── auth-service/m2.tar.gz       ← per-service Maven cache
  ├── cart-service/m2.tar.gz
  ├── order-service/m2.tar.gz
  ├── product-service/m2.tar.gz
  └── _shared/trivy-db.tar.gz      ← single shared Trivy DB
                                     (CVE data is service-agnostic)
```

### Restore Caches (early in pipeline)

1. `aws` container downloads tarballs from S3 into `$WORKSPACE/.cache/`
2. `maven` container extracts `m2.tar.gz` into `/root/.m2`
3. `tools` container extracts `trivy-db.tar.gz` into `/root/.cache/trivy`
4. Missing tarballs are non-fatal — first build for a service runs cold, then warms the cache.

### Save Caches (late, on success only)

1. `maven` re-tars `/root/.m2/repository` → `$WORKSPACE/.cache/m2.tar.gz`
2. `tools` re-tars `/root/.cache/trivy` → `$WORKSPACE/.cache/trivy-db.tar.gz`
3. `aws` uploads both back to S3 with metadata (service, gitSha, build, type)
4. Save runs **after all gates pass** — never poison the cache with broken-build state.

### Why the multi-container dance?

The `amazon/aws-cli` image is minimal — no `tar`. The `aquasec/trivy` and `maven` images have `tar` but no AWS CLI. They share the workspace `emptyDir` volume, so we move data between them via the workspace.

---

## Trade-offs we accepted

| Trade-off | Why we're OK with it |
|---|---|
| ~3–5s overhead for tar/upload | Saves 30–60s on cache hit. Net positive. |
| Cache size grows over time | S3 lifecycle policy: object replaced every successful build, no version retention. |
| Maven cross-service: per-service tarballs aren't deduplicated | Storage cost is pennies. Avoids dep version conflicts. |
| Trivy DB cache is rebuilt-fresh per day | Trivy refreshes its DB every 24h anyway. Our cache is at most 24h stale, same as upstream. |
| First build after cache miss is slow | Acceptable. Subsequent builds win. |

---

## Future evolution path

This pattern scales to ~50 services / ~50 builds/day. Beyond that:

1. **~50 services:** Move to **Gradle/Maven build-cache extension** (action-level, content-addressed) running as a Deployment in EKS.
2. **~200 services:** Adopt managed remote build cache SaaS — Gradle Develocity, BuildBuddy, or Nx Cloud.
3. **Hyperscale:** Bazel + custom remote execution farm. Not relevant for application-CI workloads.

The S3 cache will keep working as long as you don't outgrow tarball-based caching. No urgent rip-and-replace needed.

---

## Interview talking points

**Q: How do you cache builds?**
A: S3 tarball cache, same pattern as GitHub Actions and GitLab CI. Restored at start, saved at end on success. Multi-AZ for free via S3, no PVC complexity.

**Q: Why not EBS PVC?**
A: AZ pinning is anti-pattern for stateless workloads. ReadWriteOnce blocks parallel builds. Cache cross-contamination needs per-service PVCs which multiply orphan volume costs. Attach latency eats savings.

**Q: Why not EFS?**
A: Maven's `.m2` has tens of thousands of tiny files. EFS metadata latency makes the cache slower than no cache for that workload. Acceptable for Trivy but not worth running two storage systems.

**Q: When would you use PVCs?**
A: For databases, queues, observability stores — anything where data is irreplaceable, single-writer is desired, and attach latency is amortized over long-running pods. Not for ephemeral build agents.

**Q: What's the next evolution if this gets slow?**
A: Maven Build Cache Extension (action-level, fingerprint-based) → Gradle Develocity SaaS for cross-service deduplication.
