# Backend Explanation

For the backend, we used Java and Spring Boot. The backend was designed as a microservice-based application, where different business modules were separated into independent services.

Maven was used for dependency management, unit testing, and building the Spring Boot applications. After the build, the services were packaged and containerized.

On AWS, the backend services were deployed on Amazon EKS. Each service was running as Kubernetes pods and was exposed internally using Kubernetes Services. For external traffic, we used Kubernetes Ingress with AWS Application Load Balancer.

CloudFront was configured with a separate backend origin. So when the user accessed frontend pages, CloudFront served content from S3, but when the browser made API calls, CloudFront routed those API requests to the ALB origin. From ALB, traffic reached the correct backend service running inside EKS.

The backend services connected to Aurora Serverless PostgreSQL v2, which was deployed in private subnets.

# Database Flow

For the database, we used Amazon Aurora Serverless PostgreSQL v2.

Aurora was deployed in private database subnets, so it was not directly accessible from the internet. Only backend services running inside the VPC were allowed to connect to the database through security group rules.

Backend Spring Boot services connected to Aurora PostgreSQL for storing application data such as users, schools, pupils, parents, staff, messages, payments, homework, reports, and configuration data.

## Backend request flow

```text
User
  → CloudFront + WAF
  → S3
  → React frontend loads in browser
```

For backend API access:

```text
User Browser
  → CloudFront + WAF
  → ALB
  → EKS Ingress
  → Kubernetes Service
  → Spring Boot Microservice Pod
  → Aurora PostgreSQL
```
