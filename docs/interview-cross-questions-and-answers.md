# ShopEase Architecture - Interview Cross-Questions & Real-Time Answers

This document lists common cross-questions, real-time scenarios, and general questions an interviewer might ask about the ShopEase AWS/EKS architecture, along with crisp, interview-ready answers.

---

## 1. Database & Data Flow

**Q: How do you handle database schema migrations in production?**
A: We use Flyway or Liquibase as part of the CI/CD workflow. Migrations are versioned, reviewed with the application change, and applied in a controlled deployment step. For risky changes, we use backward-compatible migrations, backups, and a tested rollback or forward-fix plan.

**Q: What happens if Aurora fails over during a transaction?**
A: Aurora promotes a healthy replica in another Availability Zone and updates the cluster endpoint. Any in-flight transaction can fail, so the application should use connection pooling, timeout handling, and retry logic for safe, idempotent operations.

**Q: How do you ensure data consistency across microservices?**
A: Each service owns its own schema or set of tables in the shared Aurora cluster. For local changes, we keep strong consistency inside the service transaction. For cross-service workflows, we avoid distributed transactions and use event-driven patterns such as the outbox pattern for eventual consistency.

**Q: How do you manage database credential rotation?**
A: Database credentials are stored in AWS Secrets Manager. External Secrets Operator syncs them into Kubernetes Secrets, and access is scoped through IAM. If secrets are consumed as environment variables, pods need a restart to pick up rotated values; if mounted as volumes, updates can be picked up without rebuilding the image.

---

## 2. Security

**Q: How do you prevent unauthorized access to the database?**
A: Aurora is deployed in private subnets and is not publicly accessible. Security groups allow database traffic only from the application layer, and credentials are stored outside the image in AWS Secrets Manager. IAM, RBAC, and least-privilege policies limit who and what can access the secret.

**Q: What would happen if a pod is compromised? Can it access other resources?**
A: The blast radius should be limited by IRSA, Kubernetes RBAC, network policies, and scoped security groups. A compromised pod should only have the IAM permissions and network paths required for that service, not broad access to AWS or the cluster.

**Q: How do you handle secrets management for third-party integrations?**
A: Third-party secrets are stored in AWS Secrets Manager and synced to the cluster through External Secrets Operator. Each service receives only the secrets it needs, and IAM policies restrict read access to specific secret ARNs.

---

## 3. Scaling & Reliability

**Q: How does Aurora Serverless handle sudden spikes in traffic?**
A: Aurora Serverless v2 adjusts ACUs based on load while keeping the database highly available. For predictable spikes, we can set appropriate minimum capacity or pre-warm capacity to avoid cold scaling effects. Application-side connection pooling also matters because database scaling does not fix connection storms by itself.

**Q: What is your strategy for scaling the EKS cluster and pods?**
A: Pods scale through HPA based on CPU, memory, or custom metrics. Nodes scale through Cluster Autoscaler or Karpenter when pending pods need capacity. We also set resource requests and limits so the scheduler and autoscaler can make reliable decisions.

**Q: How do you ensure zero-downtime deployments?**
A: We use Kubernetes rolling updates with readiness probes, liveness probes, and sensible `maxUnavailable` and `maxSurge` values. The ALB routes only to healthy targets, and pods are not added to service endpoints until readiness passes. For graceful shutdown, the app should handle SIGTERM and allow in-flight requests to finish.

**Q: What is your disaster recovery plan for the database?**
A: Aurora provides automated backups, point-in-time recovery, snapshots, and multi-AZ failover. We also need tested restore procedures, documented RTO/RPO targets, and periodic recovery drills so the DR plan is proven, not just configured.

---

## 4. Networking & Routing

**Q: How does the ALB know which pod to send traffic to?**
A: The AWS Load Balancer Controller watches Kubernetes Ingress and Service resources, then registers healthy pod IPs in the ALB target group when `target-type: ip` is used. The ALB forwards requests to targets that pass health checks.

**Q: What happens if a pod IP changes? How is the ALB updated?**
A: Pod IPs are ephemeral, so the controller continuously reconciles Kubernetes state with AWS target groups. When pods are created, replaced, or terminated, their target registration is updated automatically.

**Q: Why did you choose `target-type: ip` over `instance` for the ALB?**
A: `ip` mode lets the ALB route directly to pod IPs, which avoids an extra NodePort hop and fits the EKS networking model well. It also gives more accurate target health because the ALB checks individual pods instead of only checking nodes.

**Q: How do you handle CORS and security at the edge?**
A: CloudFront enforces HTTPS, origin access control for S3, and cache or response header policies where appropriate. CORS is usually handled by the application or CloudFront response headers, while authentication and authorization are enforced in the backend services.

---

## 5. CI/CD & Operations

**Q: How do you roll back a bad deployment?**
A: We roll back to the last known good image tag, usually a Git SHA, through the deployment pipeline. Kubernetes rollout history can also revert a Deployment, but the safer process is to redeploy a verified image and confirm health checks, logs, and key business flows.

**Q: How do you promote an image from dev to prod?**
A: The same immutable image, tagged by Git SHA, is promoted across environments. We do not rebuild for production; we change environment-specific configuration and run the deployment pipeline with the approved target environment.

**Q: What is your process for hotfixes in production?**
A: A hotfix branch is created from the production baseline, tested, reviewed, and deployed through the same pipeline. After release, the fix is merged back into the main development branch to avoid drift.

**Q: How do you monitor and alert on failed deployments or unhealthy pods?**
A: We monitor Kubernetes rollout status, pod restart counts, ALB target health, application error rates, latency, and database metrics. Alerts can come from CloudWatch and Prometheus/Grafana, with logs used to diagnose the failing component.

---

## 6. Real-Time Scenarios

**Q: If a user reports slow checkout, how do you troubleshoot?**
A: I would first identify whether the issue is global or user-specific, then check CloudFront, ALB latency, pod CPU/memory, application logs, database query time, and external dependencies. Tracing is useful to find exactly which hop is slow.

**Q: If the `/api/auth/login` endpoint is failing, how do you debug the issue?**
A: I would check CloudFront and ALB status codes, ALB target health, the auth service pod logs, recent deployments, Kubernetes events, and database connectivity. If the failure started after a release and the root cause is not immediately clear, I would roll back to restore service while continuing investigation.

**Q: If CloudFront is serving stale content, what steps do you take?**
A: I would verify the object in S3, check CloudFront cache behavior and cache-control headers, and invalidate only the affected paths when needed. For frequent frontend releases, hashed asset names reduce the need for broad invalidations.

**Q: If a pod is repeatedly failing health checks, what is your approach?**
A: I would inspect pod logs, `kubectl describe pod`, events, probe configuration, resource limits, and recent image or config changes. Common causes are slow startup, wrong health endpoint, missing secrets, database connectivity issues, or CPU/memory pressure.

---

## 7. General Questions

**Q: Why did you choose Aurora Serverless over standard RDS or DynamoDB?**
A: Aurora Serverless v2 gives PostgreSQL compatibility, relational transactions, high availability, and automatic capacity scaling. It fits an e-commerce workload where orders, carts, and user data benefit from relational modeling. DynamoDB is excellent for key-value access patterns, but it would require a different data model and consistency approach.

**Q: What are the trade-offs of using a shared ALB for all services?**
A: A shared ALB reduces cost and simplifies ingress management, but it requires careful rule ownership, path priority management, and monitoring because multiple services depend on the same entry point. The ALB itself is managed and multi-AZ, but misconfiguration can still affect several services at once.

**Q: How do you secure communication between services inside the cluster?**
A: We restrict traffic with Kubernetes network policies and keep services private inside the cluster. For stronger service-to-service identity and encryption, we can add mTLS through a service mesh such as Istio or Linkerd.

**Q: How do you handle blue/green or canary deployments?**
A: The current setup uses rolling updates. For canary or blue/green strategies, we can use Argo Rollouts, Flagger, or weighted routing with the ingress layer to shift traffic gradually and monitor metrics before full promotion.

**Q: What cost optimization strategies have you implemented?**
A: We use a shared ALB, Aurora Serverless v2, right-sized Kubernetes resources, and autoscaling. Additional optimizations include Spot nodes for stateless workloads, lifecycle policies for logs and images, and regular review of idle resources.

**Q: How would you extend this architecture for multi-region or multi-cloud?**
A: For multi-region on AWS, I would add another EKS cluster, use Aurora Global Database or cross-region replication, replicate secrets and container images, and use Route 53 for failover or latency-based routing. For multi-cloud, I would keep Kubernetes and Terraform patterns portable, but I would be careful with managed-service differences and data replication complexity.

**Q: What are the limitations of your current setup, and how would you address them?**
A: Current limitations include limited multi-region recovery, no full service mesh, and manual cache invalidation for some frontend changes. I would address these by testing DR, adding better observability and tracing, automating frontend cache strategy, and introducing mTLS or a service mesh only when the operational value justifies the added complexity.

---
