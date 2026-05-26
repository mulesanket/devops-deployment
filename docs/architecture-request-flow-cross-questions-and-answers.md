# ShopEase Architecture & Request Flow - Cross-Questions and Answers

This document covers likely interviewer cross-questions from the ShopEase architecture and request-flow discussion. The answers are intentionally concise, practical, and tied to the actual AWS/EKS design.

---

## 1. Edge Layer: CloudFront and S3

**Q: Why is CloudFront used in front of both S3 and the ALB?**
A: CloudFront gives a single public entry point for the application. It serves static React assets from S3 with low latency and forwards API requests to the ALB. It also helps enforce HTTPS, caching policies, and origin controls.

**Q: Why not expose the S3 bucket directly to users?**
A: Direct public S3 access is avoided for security. The bucket should stay private, and CloudFront should access it using Origin Access Control. That way users access the frontend only through the CloudFront distribution.

**Q: How does CloudFront decide whether to send traffic to S3 or the ALB?**
A: CloudFront uses cache behaviors and path patterns. The default behavior can serve the React app from S3, while `/api/*` routes are forwarded to the ALB origin.

**Q: What happens when a user refreshes a React route such as `/orders`?**
A: Since React is a single-page app, CloudFront/S3 should return `index.html` for frontend routes. This is usually handled with CloudFront custom error responses or rewrite logic so the client-side router can load the correct page.

**Q: How do you prevent stale frontend files after deployment?**
A: Static assets should be built with hashed filenames and long cache TTLs, while `index.html` should have a shorter TTL or be invalidated during deployment. This avoids broad cache invalidations for every release.

---

## 2. ALB, Ingress, and Routing

**Q: Why use an ALB instead of exposing each service with a LoadBalancer Service?**
A: A LoadBalancer Service would usually create a separate load balancer per service, which increases cost and operational overhead. A shared ALB with Ingress path rules lets multiple services share one external entry point.

**Q: How do all microservices share the same ALB?**
A: Each service has its own Kubernetes Ingress, and all Ingress resources use the same `alb.ingress.kubernetes.io/group.name` annotation. The AWS Load Balancer Controller merges them into one ALB using the IngressGroup pattern.

**Q: What is the role of the AWS Load Balancer Controller?**
A: It watches Kubernetes Ingress, Service, and Endpoint resources and reconciles them with AWS ALB resources. It creates or updates listeners, rules, target groups, and target registrations based on Kubernetes state.

**Q: What happens when a new pod is created for a service?**
A: Kubernetes assigns the pod an IP. The AWS Load Balancer Controller observes the endpoint update and registers that pod IP in the correct ALB target group. Once health checks pass, the ALB can route traffic to it.

**Q: Why is `target-type: ip` used?**
A: `target-type: ip` lets the ALB route directly to pod IPs instead of routing through node ports. This gives more accurate pod-level health checks and avoids an extra hop through the node.

**Q: What would change if `target-type: instance` was used instead?**
A: The ALB would target worker nodes through NodePort, and Kubernetes networking would forward traffic from the node to a pod. It works, but target health is node-level rather than direct pod-level, and the request path has an extra hop.

**Q: How are path conflicts handled between Ingress resources?**
A: Path rules need to be designed carefully because all grouped Ingresses share the same ALB. More specific paths should be used for services, and rule priorities or path definitions must avoid overlap such as two services claiming the same `/api/auth` path.

---

## 3. Request Flow

**Q: Explain the request flow for `/api/auth/login`.**
A: The browser sends the request to CloudFront. CloudFront matches `/api/*` and forwards it to the ALB. The ALB matches the `/api/auth` path rule and forwards the request to the auth service target group. The ALB target group contains healthy auth pod IPs, so the request goes directly to one auth pod.

**Q: Where does TLS terminate in this architecture?**
A: TLS terminates at CloudFront for the public user connection. CloudFront can also use HTTPS when connecting to the ALB if configured that way. Inside the VPC, traffic can be HTTP or HTTPS depending on the service requirements and security posture.

**Q: Does the request go through the Kubernetes Service if ALB targets pod IPs?**
A: The Service is still important because it gives the controller a stable way to discover which pods belong to the backend. However, with `target-type: ip`, the ALB sends traffic directly to registered pod IPs rather than to the Service ClusterIP.

**Q: How does the ALB know a pod is healthy?**
A: The ALB runs target group health checks against the registered pod IPs and ports. Kubernetes readiness probes should align with the same application health concept so traffic is sent only to pods that can actually serve requests.

**Q: What happens during a rolling deployment?**
A: Kubernetes creates new pods and waits for readiness. The controller registers new pod IPs with the ALB target group. As old pods terminate, they are deregistered and drained from the target group. This allows traffic to move gradually to the new version.

**Q: What happens if one pod crashes?**
A: Kubernetes restarts or replaces the failed pod through the Deployment controller. The failed pod stops passing readiness and ALB health checks, so it is removed from traffic. Other healthy replicas continue serving requests.

---

## 4. EKS Application Layer

**Q: Why are the microservices deployed as separate services?**
A: Auth, product, cart, and order have different responsibilities and can be deployed, scaled, and monitored independently. This separation also makes ownership and troubleshooting clearer.

**Q: Why are Kubernetes Services still needed if Ingress routes traffic?**
A: Ingress defines external HTTP routing, but it points to Kubernetes Services as backends. Services provide stable discovery and label-based selection for the pods behind each microservice.

**Q: How does Kubernetes know which pods belong to which service?**
A: A Service uses label selectors. For example, the auth Service selects pods with auth-specific labels, and Kubernetes keeps the matching endpoints updated as pods change.

**Q: Why use private subnets for EKS workloads?**
A: Application pods and worker nodes do not need direct public exposure. Keeping them in private subnets reduces the attack surface; public traffic enters through CloudFront and the ALB only.

**Q: How do pods access AWS services like Secrets Manager, SNS, or SQS?**
A: Pods should use IAM Roles for Service Accounts, known as IRSA. This maps a Kubernetes service account to a scoped IAM role so each workload gets only the AWS permissions it needs.

---

## 5. Data and Async Flow

**Q: Why is Aurora PostgreSQL Serverless v2 used for persistence?**
A: Aurora PostgreSQL gives relational consistency and PostgreSQL compatibility, while Serverless v2 can adjust capacity based on workload. This fits transactional e-commerce data such as users, carts, products, and orders.

**Q: Why is Aurora deployed in private subnets?**
A: The database should not be reachable from the public internet. Only approved application workloads inside the VPC should connect to it through tightly scoped security groups.

**Q: How do microservices connect to the database securely?**
A: They use the Aurora internal endpoint, security groups that allow only application traffic, encrypted connections where configured, and credentials from AWS Secrets Manager rather than hardcoded values.

**Q: Is using one Aurora cluster for all microservices a problem?**
A: It is acceptable for a cost-conscious project if each service owns its schema or tables and boundaries are respected. The trade-off is that the database becomes a shared dependency, so access patterns, migrations, and failure impact must be managed carefully.

**Q: Why use SNS, SQS, Lambda, and SES for email instead of sending email directly from the service?**
A: Asynchronous messaging decouples the user-facing request from email delivery. The auth service can publish an event quickly, SQS buffers it reliably, Lambda processes it, and SES sends the email without slowing down signup.

**Q: What happens if email sending fails?**
A: The message can remain in SQS and be retried according to the queue and Lambda retry configuration. Failed messages can be moved to a dead-letter queue for later investigation.

---

## 6. Security and Secrets

**Q: Why use AWS Secrets Manager with External Secrets Operator?**
A: Secrets Manager provides centralized secret storage, rotation support, auditability, and IAM-based access control. External Secrets Operator syncs selected secrets into Kubernetes so pods can consume them without storing secrets in Git.

**Q: Are Kubernetes Secrets enough by themselves?**
A: Kubernetes Secrets are useful inside the cluster, but they are not a full external secret management system. Using Secrets Manager gives stronger integration with AWS IAM, rotation, audit trails, and centralized management.

**Q: How do you stop one service from reading another service's secret?**
A: Use separate Kubernetes service accounts, scoped IAM policies, and ExternalSecret definitions that reference only the secrets required by that service. RBAC should also prevent broad secret reads inside the cluster.

**Q: How is database access restricted at the network level?**
A: The Aurora security group allows inbound traffic only from the EKS application security group or specific worker node/pod security groups, depending on the networking setup. No public inbound database access is allowed.

**Q: How would you improve service-to-service security?**
A: Add Kubernetes network policies for east-west traffic control and consider mTLS through a service mesh when service identity and encrypted internal traffic become operational priorities.

---

## 7. Reliability, Scaling, and Operations

**Q: What are the main high-availability points in this design?**
A: CloudFront is global, the ALB is multi-AZ, EKS worker nodes should run across multiple Availability Zones, and Aurora provides multi-AZ storage and failover. The application also needs multiple pod replicas to benefit from this infrastructure.

**Q: What is the biggest shared dependency in this architecture?**
A: The shared ALB and shared Aurora cluster are important dependencies. They are managed and highly available, but misconfiguration, bad rules, connection exhaustion, or database issues can affect multiple services.

**Q: How do you scale the application layer?**
A: Use HPA to scale pod replicas based on CPU, memory, or application metrics. Use Cluster Autoscaler or Karpenter to add nodes when the cluster lacks capacity for new pods.

**Q: How do you scale the database layer?**
A: Aurora Serverless v2 adjusts capacity using ACUs. However, application connection pooling, query optimization, indexes, and sensible minimum/maximum ACU settings are still necessary for stable performance.

**Q: What monitoring would you configure for this request flow?**
A: Monitor CloudFront status codes and cache behavior, ALB latency and target health, pod CPU/memory/restarts, application logs, database connections and query latency, and SQS/Lambda failures for async flows.

**Q: If users report intermittent 502 errors, where would you check first?**
A: Start with ALB target health and status-code metrics, then check pod readiness, application logs, recent deployments, and whether the backend service is listening on the expected port. A 502 often means the ALB could not get a valid response from the target.

---

## 8. Common Trade-Off Questions

**Q: What is the trade-off of this architecture?**
A: It is cost-aware and practical because it uses a shared ALB, managed database, and managed AWS services. The trade-off is that shared components require disciplined configuration, monitoring, and ownership to avoid one change affecting multiple services.

**Q: What would you improve next?**
A: I would improve observability with tracing, add stronger network policies, automate frontend cache invalidation or asset strategy, test database restore procedures, and consider canary deployments for safer production releases.

**Q: How would this design support production traffic?**
A: It needs multiple replicas per service, autoscaling, resource requests and limits, production-grade health checks, tuned database capacity limits, alerting, backup and restore testing, and deployment rollback procedures.

**Q: How would you explain this architecture in one minute?**
A: ShopEase uses CloudFront as the public entry point. Static React assets come from private S3, while `/api/*` requests go to an ALB. The AWS Load Balancer Controller maps ALB path rules to Kubernetes Ingress resources in EKS, and the ALB routes directly to healthy pod IPs. Services store data in private Aurora PostgreSQL Serverless v2 and use SNS, SQS, Lambda, and SES for asynchronous email. Secrets are managed through AWS Secrets Manager and synced into Kubernetes with External Secrets Operator.

---
