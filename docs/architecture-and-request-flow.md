# ShopEase Architecture and Request Flow

## 1. Architecture Overview

- **Edge Layer:**
  - All user traffic enters through AWS CloudFront, which serves static frontend assets from S3 and forwards API requests to an Application Load Balancer (ALB).

- **Application Layer:**
  - The ALB is managed by the AWS Load Balancer Controller in EKS. It uses path-based routing to direct `/api/*` requests to the appropriate microservice in the EKS cluster.
  - Each microservice (auth, product, cart, order) is exposed via a Kubernetes Ingress resource.
  - All Ingresses share a single ALB using the `alb.ingress.kubernetes.io/group.name` annotation (IngressGroup pattern), which is cost-effective and easy to manage.
  - The Ingress for each service defines a path rule (e.g., `/api/auth`) and uses `target-type: ip`, so the ALB routes traffic directly to the pod IPs.

- **Data & Async Layer:**
  - Services interact with Aurora PostgreSQL for persistence and use AWS SNS/SQS/Lambda/SES for asynchronous operations like sending emails.

- **Security & Secrets:**
  - Database credentials and other secrets are managed via AWS Secrets Manager and injected into pods using the External Secrets Operator.
  - All database and internal service traffic is restricted to private subnets and tightly scoped security groups.

## 2. Request Flow (Frontend to Pod)

1. **User Action:**
   - The user interacts with the React frontend (served from S3 via CloudFront).

2. **API Call:**
   - The frontend makes a request to an API endpoint (e.g., `/api/auth/login`).

3. **CloudFront Routing:**
   - CloudFront forwards `/api/*` requests to the ALB.

4. **ALB Path Rule:**
   - The ALB, using path-based rules, matches the request (e.g., `/api/auth/*`) and forwards it to the EKS cluster.

5. **Ingress Rule:**
   - The relevant Ingress in EKS matches the path prefix and points to the correct backend service.

6. **Pod Targeting (target-type: ip):**
   - The AWS Load Balancer Controller registers the actual pod IPs (not the Service ClusterIP) as ALB targets.
   - The ALB sends the request directly to a healthy pod on its container port.

7. **Service Logic:**
   - The pod processes the request, interacts with the database if needed, and returns a response.

8. **Response Path:**
   - The response travels back: pod → ALB → CloudFront → user’s browser.

## 3. Database Layer

- **Database Choice:**
  - Amazon Aurora PostgreSQL Serverless v2, fully managed, highly available, and auto-scaling.

- **Deployment:**
  - Deployed in private subnets across three Availability Zones for high availability and fault tolerance.
  - Not publicly accessible—only workloads inside the VPC (like EKS) can connect.

- **Connectivity & Security:**
  - All microservices connect to Aurora using internal endpoints.
  - Security Groups are tightly scoped: only EKS worker nodes are allowed to connect.
  - Credentials are managed via AWS Secrets Manager and injected into pods at runtime via  the External  Secrets Operator—no hardcoded secrets in code or config.

- **Resilience:**
  - Aurora provides automatic failover and point-in-time recovery.

- **Scaling:**
  - Aurora Serverless v2 automatically adjusts capacity based on load.

- **Usage Pattern:**
  - Each microservice has its own schema or set of tables, but all share the same Aurora cluster for transactional consistency.
  - All data access is over encrypted connections.

---

This document summarizes the core AWS architecture, request flow, and database design for ShopEase, highlighting security, scalability, and operational best practices.
