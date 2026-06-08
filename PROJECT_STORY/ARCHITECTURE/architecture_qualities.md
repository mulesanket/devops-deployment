# Project Architecture Qualities

## 1. High Availability

We ensured high availability at multiple layers: frontend, backend, and database.

For the frontend, we used **Amazon S3 with CloudFront**. Since CloudFront is globally distributed, users could access the application with low latency and better availability.

For the backend, our **Spring Boot microservices were deployed on Amazon EKS** with multiple pod replicas. Worker nodes were spread across multiple Availability Zones, and **ALB Ingress** was used to route traffic only to healthy backend pods.

For the database, we used **Amazon Aurora Serverless PostgreSQL v2** in private database subnets. Aurora provides managed high availability and failover support.

So if one backend pod failed, Kubernetes could recreate it. If one node failed, pods could be rescheduled on another healthy node, and ALB continued sending traffic only to healthy targets.

---

## 2. Scalability

Scalability was handled using **CloudFront, EKS, Aurora Serverless v2, SQS, and Lambda**.

For the frontend, CloudFront cached React static assets at edge locations. So repeated requests were served from cache instead of hitting the S3 origin every time.

For the backend, since services were running on EKS, we could scale individual microservices horizontally by increasing pod replicas. HPA was used or could be configured to scale pods based on CPU and memory usage.

For the database, Aurora Serverless PostgreSQL v2 helped scale database capacity based on workload.

For user signup and email notification flow, SQS buffered the messages and Lambda processed them independently. This helped us handle sudden signup spikes without putting direct pressure on the backend service.

---

## 3. Security

Security was implemented at different layers of the architecture.

At the edge layer, **AWS WAF was attached to CloudFront** to protect the application from common web attacks like SQL injection, cross-site scripting, bad bots, and unwanted traffic.

For the frontend, static files were hosted in S3 and served through CloudFront. Users accessed the application through CloudFront instead of directly accessing the S3 bucket.

For the backend, APIs were exposed through **CloudFront and ALB Ingress**. Backend pods were not directly exposed to the internet.

For the database, **Aurora PostgreSQL was deployed in private subnets** with no public access. Security groups allowed database access only from backend services running inside the VPC/EKS.

For AWS service access like S3, SNS, SQS, SES, and other services, IAM roles and least-privilege permissions were used wherever applicable.

---

## 4. Integration

Integration was mainly between frontend, backend, database, and AWS managed services.

CloudFront was configured with multiple origins. Static frontend requests were routed to the S3 origin, and API requests were routed to the ALB origin.

The ALB routed API traffic to Kubernetes Ingress, then to the correct Kubernetes Service, and finally to the Spring Boot microservice pods running on EKS.

Backend services connected to Aurora Serverless PostgreSQL v2 for relational data storage.

For new user signup and email notification flow, the backend published an event to SNS/SQS. Lambda consumed the message and sent emails using Amazon SES.

This made the architecture modular, event-driven, and loosely coupled.

---

## 5. Fault Tolerance

Fault tolerance was handled using Kubernetes and AWS managed services.

If a backend pod failed, Kubernetes restarted it automatically. If a node failed, pods could be rescheduled on another healthy node.

ALB health checks ensured that traffic was routed only to healthy backend targets.

For signup and email processing, SQS acted as a buffer. If Lambda or SES had a temporary issue, messages could remain in the queue and be retried instead of being lost immediately.

Aurora also provided managed database availability and failover support.

So the application was not dependent on a single pod, node, or synchronous email process.

---

## 6. Backups and Recovery

For database backup, Aurora automated backups were enabled, and point-in-time recovery could be used to restore the database to a previous state if required.

For frontend recovery, S3 versioning helped maintain previous versions of static build files.

For backend rollback, Docker images were stored in ECR with version tags. If a new deployment had issues, we could redeploy a previous stable image version.

Kubernetes manifests, Helm charts, and Terraform code were maintained in Git, so infrastructure and application configuration could be recreated if required.

---

# Final Interview Answer

In our project, we ensured high availability, scalability, security, integration, fault tolerance, and backups using AWS managed services and Kubernetes.

The frontend was hosted on S3 and delivered through CloudFront, with AWS WAF enabled for edge-level protection. The backend Spring Boot microservices were deployed on EKS with multiple replicas, ALB Ingress, health checks, and rolling deployments. Aurora Serverless PostgreSQL v2 was used in private subnets for database availability and scaling.

For integration, CloudFront routed frontend traffic to S3 and API traffic to ALB. Backend services integrated with Aurora for database operations and with SNS, SQS, Lambda, and SES for asynchronous signup and email workflows.

For fault tolerance, Kubernetes restarted failed pods, ALB routed traffic only to healthy targets, SQS buffered asynchronous messages, and Aurora provided managed failover support.

For backups and recovery, Aurora automated backups, S3 versioning, ECR image tags, and Git-based infrastructure/application configuration helped us recover or roll back when needed.
