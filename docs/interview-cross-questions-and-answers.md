# ShopEase Architecture — Interview Cross-Questions & Real-Time Answers

This document lists common cross-questions, real-time scenarios, and general questions an interviewer might ask about the ShopEase AWS/EKS architecture, along with crisp, interview-ready answers.

---

## 1. Database & Data Flow

**Q: How do you handle database schema migrations in production?**
A: We use Flyway (or Liquibase) integrated into our CI/CD pipeline. Migrations are versioned and applied automatically during deployment, with rollback support and pre-deployment backups for safety.

**Q: What happens if Aurora fails over during a transaction?**
A: Aurora automatically promotes a replica in another AZ. The application uses retry logic and connection pooling, so transient errors are retried and the impact is minimal.

**Q: How do you ensure data consistency across microservices?**
A: Each service has its own schema or tables in the shared Aurora cluster. For cross-service consistency, we use transactional boundaries and, where needed, outbox/event-driven patterns for eventual consistency.

**Q: How do you manage database credentials rotation?**
A: Credentials are stored in AWS Secrets Manager. Rotation is automated, and the External Secrets Operator ensures pods always get the latest version without redeploys.

---

## 2. Security

**Q: How do you prevent unauthorized access to the database?**
A: The DB is in private subnets, and security groups only allow connections from EKS worker nodes. IAM and RBAC restrict pod access, and credentials are never hardcoded.

**Q: What would happen if a pod is compromised? Can it access other resources?**
A: Pod IAM (IRSA) and network policies restrict each pod’s permissions to only what it needs. Even if compromised, blast radius is limited to that service’s scope.

**Q: How do you handle secrets management for third-party integrations?**
A: Third-party secrets are also stored in AWS Secrets Manager and injected into pods at runtime. Access is tightly scoped via IAM policies.

---

## 3. Scaling & Reliability

**Q: How does Aurora Serverless handle sudden spikes in traffic?**
A: Aurora Serverless v2 scales ACUs up or down automatically based on load, with minimal latency. For extreme spikes, we monitor and can pre-provision capacity if needed.

**Q: What’s your strategy for scaling the EKS cluster and pods?**
A: We use Cluster Autoscaler for node scaling and HPA (Horizontal Pod Autoscaler) for pods, based on CPU/memory and custom metrics.

**Q: How do you ensure zero-downtime deployments?**
A: We use rolling updates in Kubernetes, readiness/liveness probes, and the ALB only routes to healthy pods. No traffic is sent to pods until they’re ready.

**Q: What’s your disaster recovery plan for the database?**
A: Aurora provides automated backups and point-in-time recovery. We regularly test restores and have runbooks for failover and recovery.

---

## 4. Networking & Routing

**Q: How does the ALB know which pod to send traffic to?**
A: The AWS Load Balancer Controller registers pod IPs as ALB targets based on the Service selector. The ALB routes directly to healthy pods using `target-type: ip`.

**Q: What happens if a pod IP changes? How is the ALB updated?**
A: The controller watches for pod changes and updates the ALB target group in real time, ensuring only healthy, current pods receive traffic.

**Q: Why did you choose `target-type: ip` over `instance` for the ALB?**
A: `ip` mode allows direct routing to pods, reducing latency and avoiding the kube-proxy hop. It’s the AWS best practice for EKS.

**Q: How do you handle CORS and security at the edge?**
A: All API traffic goes through CloudFront, which enforces HTTPS and origin policies. CORS is handled at the ALB and application layer as needed.

---

## 5. CI/CD & Operations

**Q: How do you roll back a bad deployment?**
A: We can roll back to a previous image by re-running the CD pipeline with the last known good git SHA. Kubernetes also supports rolling back deployments natively.

**Q: How do you promote an image from dev to prod?**
A: The same image (tagged by git SHA) is promoted across environments by re-running the CD pipeline with the desired environment parameter—no rebuilds.

**Q: What’s your process for hotfixes in production?**
A: Hotfixes are branched from `master`, tested, and merged back. The pipeline ensures only approved, tested images reach production.

**Q: How do you monitor and alert on failed deployments or unhealthy pods?**
A: We use CloudWatch, Prometheus, and ALB health checks for monitoring. Alerts are set up for failed deployments, unhealthy pods, and error rates.

---

## 6. Real-Time Scenarios

**Q: If a user reports slow checkout, how do you troubleshoot?**
A: Start with ALB and CloudFront metrics, then check pod and DB metrics (CPU, memory, query times). Use distributed tracing and logs to pinpoint bottlenecks.

**Q: If the `/api/auth/login` endpoint is failing, how do you debug the issue?**
A: Check ALB target health, pod logs, and application metrics. Verify DB connectivity and recent deployments. Roll back if needed.

**Q: If CloudFront is serving stale content, what steps do you take?**
A: Invalidate the CloudFront cache for affected paths and verify S3 content. Check cache-control headers in the app.

**Q: If a pod is repeatedly failing health checks, what’s your approach?**
A: Inspect pod logs, events, and recent changes. Check resource limits and readiness/liveness probe configs. Roll back or redeploy as needed.

---

## 7. General Questions

**Q: Why did you choose Aurora Serverless over RDS or DynamoDB?**
A: Aurora Serverless offers auto-scaling, high availability, and PostgreSQL compatibility, making it ideal for variable workloads and transactional consistency.

**Q: What are the trade-offs of using a shared ALB for all services?**
A: It saves cost and simplifies management, but requires careful path rule management and can be a single point of failure if not monitored.

**Q: How do you secure communication between services inside the cluster?**
A: We use Kubernetes network policies and mTLS (planned) to restrict and encrypt inter-service traffic.

**Q: How do you handle blue/green or canary deployments?**
A: Currently, we use rolling updates. For advanced strategies, we can integrate Argo Rollouts or Flagger for traffic shifting and canaries.

**Q: What are the cost optimization strategies you’ve implemented?**
A: Shared ALB, Aurora Serverless, spot nodes (planned), and right-sized resources. We monitor usage and adjust as needed.

**Q: How would you extend this architecture for multi-region or multi-cloud?**
A: Add another EKS cluster and Aurora Global DB in a new region, use Route 53 for DNS, and replicate secrets and configs. For multi-cloud, use cloud-agnostic tools and CI/CD.

**Q: What are the limitations of your current setup, and how would you address them?**
A: Current limitations include lack of service mesh, limited multi-region support, and manual cache invalidation. We plan to add Istio, automate DR, and improve observability.

---
