# Database Explanation

### Database technology:

Amazon Aurora Serverless PostgreSQL v2

### Purpose:

Relational database for application data

### Placement:

Private database subnets

### Access:

Only backend microservices inside VPC/EKS can connect

### Security:

Security groups, private networking, no public access

For the database layer, we used Amazon Aurora Serverless PostgreSQL v2.

Since School Spider is a school communication and management platform, we had relational data such as schools, users, parents, pupils, staff, roles, permissions, messages, homework, surveys, payments, reports, and configuration details.

Aurora PostgreSQL was used because the application needed a reliable relational database with PostgreSQL compatibility, high availability, automated backups, and managed database operations.

Aurora Serverless v2 helped us handle variable traffic patterns. For example, school application usage may increase during parent communication, payment activities, parents evening bookings, reports, or announcements. With Serverless v2, database capacity could scale based on workload without manually resizing database instances.

## Database Network Flow

The Aurora database was deployed in private database subnets inside the VPC. It was not publicly accessible from the internet.

Only backend services running inside EKS were allowed to connect to Aurora through security group rules.

So the database request flow was:

```text
Spring Boot Microservice Pod
  → Kubernetes networking
  → VPC private networking
  → Aurora Serverless PostgreSQL v2
```

## Security Explanation

From a security point of view, Aurora was kept private and was not exposed directly to external users.

The database security group allowed inbound PostgreSQL traffic only from the backend/EKS security group. This ensured that only application services could access the database.

Database credentials were not hardcoded in the application. They were managed securely using secrets management, and the application consumed them during runtime.

## Why Aurora Serverless PostgreSQL v2 was used

We used Aurora Serverless PostgreSQL v2 mainly for four reasons:

### 1. PostgreSQL compatibility

The backend was designed around relational data, and PostgreSQL was suitable for structured school, user, payment, and communication data.

### 2. Managed high availability

Aurora is managed by AWS and supports high availability across Availability Zones.

### 3. Automatic scaling

Aurora Serverless v2 can scale capacity based on workload, which was useful because school application traffic is not always constant.

### 4. Reduced operational overhead

We did not have to manually manage database servers, patching, storage scaling, or instance resizing like traditional self-managed PostgreSQL.
