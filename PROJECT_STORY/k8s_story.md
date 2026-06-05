# Kubernetes Interview Story for AWS DevOps Role

## Project Context

In my project, we had multiple backend services running as containerized applications. These services were deployed on Kubernetes to improve deployment consistency, high availability, scalability, security, and fault tolerance.

The project followed a microservice-style architecture where each service had its own Kubernetes resources such as:

- Deployment
- Service
- Ingress
- ConfigMap
- Secret
- ServiceAccount
- Horizontal Pod Autoscaler
- PodDisruptionBudget
- PriorityClass
- Resource requests and limits
- Health probes
- SecurityContext

The main goal was to run services in a production-ready Kubernetes environment where deployments could happen safely, traffic could be routed properly, applications could scale based on demand, and failures could be handled automatically.

---

# 1. How Kubernetes Was Implemented in the Project

In our project, Kubernetes was used to run containerized backend services in a scalable and highly available way.

Each application service was containerized using Docker and deployed on Kubernetes using a Deployment object. The Deployment managed the desired number of Pods, rollout strategy, image version, health checks, resources, security context, and service account.

For each service, we created a Kubernetes Service to expose the Pods internally inside the cluster. The Service provided a stable internal DNS name and load-balanced traffic across healthy Pods.

For external traffic, we used Ingress with an Application Load Balancer. The Ingress handled path-based routing and forwarded traffic to the correct backend service.

Example routing pattern:

    /api/auth      → auth-service
    /api/products  → product-service
    /api/cart      → cart-service
    /api/orders    → order-service

Environment-specific configuration was managed through ConfigMaps and Secrets. Non-sensitive values like application profile, service URLs, and database endpoints were stored in ConfigMaps. Sensitive values like database passwords, tokens, and API keys were stored in Secrets or external secret managers such as AWS Secrets Manager.

We separated workloads by environment using namespaces.

Example:

    development namespace
    staging namespace
    production namespace

This helped us isolate resources, access, configuration, and deployments between environments.

---

# 2. Kubernetes Objects Used in the Project

## 2.1 Deployment

Deployment was used to manage application Pods.

It handled:

- Replica management
- Rolling updates
- Rollbacks
- Pod template changes
- Image version updates
- Self-healing if Pods failed

Example:

    apiVersion: apps/v1
    kind: Deployment
    metadata:
      name: product-service-deployment
    spec:
      replicas: 2
      strategy:
        type: RollingUpdate

The Deployment controller ensures the desired number of Pods are always running.

If desired replicas are 2 and one Pod fails, Kubernetes automatically creates another Pod.

Example:

    Desired replicas: 2
    Current running Pods: 1
    Kubernetes creates 1 new Pod

---

## 2.2 Service

Service was used to expose Pods internally inside the cluster.

Example:

    apiVersion: v1
    kind: Service
    metadata:
      name: product-service
    spec:
      type: ClusterIP
      selector:
        app: product-service-deployment
      ports:
        - port: 80
          targetPort: 8081

The Service sends traffic to Pods matching the selector.

Flow:

    Ingress / ALB
       ↓
    Kubernetes Service
       ↓
    Healthy Pod IPs
       ↓
    Container port

Important point:

    Service selector must match Pod labels.

If Service selector does not match Pod labels, Service will not have endpoints and traffic will not reach the Pods.

Command to check endpoints:

    kubectl get endpoints product-service -n <namespace>

---

## 2.3 Ingress

Ingress was used to expose services outside the cluster using HTTP path-based routing.

In AWS EKS, AWS Load Balancer Controller creates and manages an Application Load Balancer based on Ingress annotations.

Example flow:

    User request
       ↓
    AWS ALB
       ↓
    Ingress rule
       ↓
    Kubernetes Service
       ↓
    Application Pod

Example URL:

    http://alb-dns-name/api/products

This routes to:

    product-service

Path-based routing example:

    /api/auth      → auth-service
    /api/products  → product-service
    /api/cart      → cart-service
    /api/orders    → order-service

---

## 2.4 ConfigMap

ConfigMap was used for non-sensitive configuration.

Examples:

    SPRING_PROFILES_ACTIVE
    SPRING_DATASOURCE_URL
    LOG_LEVEL
    SERVICE_URL

This allowed us to keep application configuration outside the Docker image.

The same image could be promoted from dev to staging to production while changing only the ConfigMap values.

Example:

    Dev database URL       → ConfigMap value in dev namespace
    Staging database URL   → ConfigMap value in staging namespace
    Production database URL → ConfigMap value in production namespace

---

## 2.5 Secret

Secret was used for sensitive values.

Examples:

    Database password
    JWT secret
    API token
    Private key

In production, secrets should not be committed directly into Git.

A better enterprise approach is to store secrets in:

    AWS Secrets Manager
    HashiCorp Vault
    Azure Key Vault
    Google Secret Manager

Then sync or mount them into Kubernetes using:

    External Secrets Operator
    Secrets Store CSI Driver

Important point:

    Kubernetes Secret values are base64 encoded by default.
    Base64 is not encryption.
    Anyone who can read the Secret can decode it.

So production security should include:

    Encryption at rest
    Strict RBAC
    Restricted kubectl exec access
    Audit logging
    External secret management

---

## 2.6 ServiceAccount

ServiceAccount was used to give each application its own Kubernetes identity.

Instead of using the default ServiceAccount, each service had a dedicated ServiceAccount.

Example:

    product-service-sa
    auth-service-sa
    order-service-sa

This follows least-privilege practice.

If a service needs AWS access, we can attach IAM permissions using:

    IRSA
    EKS Pod Identity

Example:

    Only product-service Pods can access product-service secrets.
    Only order-service Pods can access order-related SQS queues.
    Only report-service Pods can access reporting S3 bucket.

This avoids storing static AWS access keys inside containers.

---

# 3. How Deployment Was Handled

Deployment was handled using Kubernetes Deployment with rolling update strategy.

A new application version was built in the CI/CD pipeline, pushed to a container registry like Amazon ECR, and then deployed to Kubernetes by updating the image tag.

Image tagging approach:

    Avoid: latest
    Use: Git SHA / release version / semantic version

Example:

    483829975256.dkr.ecr.ap-south-1.amazonaws.com/product-service:1.1.0

or:

    483829975256.dkr.ecr.ap-south-1.amazonaws.com/product-service:a1b2c3d

This helps with traceability and rollback.

---

## 3.1 Rolling Update Strategy

We used RollingUpdate strategy to avoid downtime during deployments.

Example:

    strategy:
      type: RollingUpdate
      rollingUpdate:
        maxUnavailable: 0
        maxSurge: 1

Meaning:

    maxUnavailable: 0
    Kubernetes should not reduce the available Pod count during rollout.

    maxSurge: 1
    Kubernetes can create one extra Pod temporarily during rollout.

Example with 2 replicas:

    Current state:
    Old Pod 1 running
    Old Pod 2 running

    During rollout:
    New Pod 1 is created
    New Pod 1 becomes Ready
    Old Pod 1 is terminated

    New Pod 2 is created
    New Pod 2 becomes Ready
    Old Pod 2 is terminated

This ensures that application availability is maintained during deployments.

---

## 3.2 Rollback

If a new version causes an issue, we can rollback using:

    kubectl rollout undo deployment/product-service-deployment -n <namespace>

Useful rollout commands:

    kubectl rollout status deployment/product-service-deployment -n <namespace>
    kubectl rollout history deployment/product-service-deployment -n <namespace>
    kubectl rollout undo deployment/product-service-deployment -n <namespace>

Interview line:

    We used Kubernetes rollout history and rollback capability to quickly recover from a faulty release. If the new image version caused application errors or probe failures, we could rollback to the previous stable ReplicaSet.

---

# 4. How High Availability Was Ensured

High availability was implemented at multiple layers.

---

## 4.1 Multiple Replicas

Critical services were deployed with more than one replica.

Example:

    replicas: 2

or production:

    replicas: 3

This ensures that if one Pod fails, another Pod can continue serving traffic.

Example:

    Pod 1 fails
    Pod 2 continues serving traffic
    Deployment creates replacement Pod

Important point:

    HA is not only about replica count.
    Replicas should also be spread across different nodes and zones.

---

## 4.2 Readiness Probe

Readiness probe was used to decide whether a Pod is ready to receive traffic.

Example:

    readinessProbe:
      httpGet:
        path: /api/products/health
        port: 8081
      periodSeconds: 30
      failureThreshold: 3

If readiness probe fails:

    Pod remains Running
    But traffic is removed from that Pod
    Service does not send traffic to it

This prevents users from hitting unhealthy or half-started Pods.

Interview line:

    Readiness probe protects users from receiving traffic on Pods that are running but not ready to serve requests.

---

## 4.3 Liveness Probe

Liveness probe was used to detect stuck or unhealthy containers.

Example:

    livenessProbe:
      httpGet:
        path: /api/products/health
        port: 8081
      initialDelaySeconds: 60
      periodSeconds: 30

If liveness probe fails repeatedly:

    Kubernetes restarts the container

This helps recover from application deadlock or hung processes.

Interview line:

    Liveness probe is used for self-healing. If the application becomes unhealthy or stuck, kubelet restarts the container.

---

## 4.4 Startup Probe

Startup probe was used for applications that take time to start, such as Java Spring Boot applications.

Example:

    startupProbe:
      httpGet:
        path: /api/products/health
        port: 8081
      failureThreshold: 30
      periodSeconds: 5

This gives the application enough time to start before liveness probe begins.

Without startup probe, Kubernetes may restart a slow-starting application too early.

Example:

    failureThreshold: 30
    periodSeconds: 5

    Total startup wait time = 30 × 5 = 150 seconds

Interview line:

    For Java-based applications, startup probe is useful because the app may take time to initialize database connections, load configurations, or warm up caches.

---

## 4.5 PodDisruptionBudget

PodDisruptionBudget protects the application during voluntary disruptions.

Examples of voluntary disruptions:

    Node drain
    Node group upgrade
    Cluster upgrade
    Cluster autoscaler scale-down
    Planned node maintenance

Example:

    apiVersion: policy/v1
    kind: PodDisruptionBudget
    spec:
      maxUnavailable: 1
      selector:
        matchLabels:
          app: product-service-deployment

Meaning:

    Maximum 1 matching Pod can be voluntarily unavailable at a time.

If the Deployment has 3 replicas:

    Total Pods: 3
    maxUnavailable: 1
    Minimum available Pods: 2

So during maintenance, Kubernetes will not evict all Pods at once.

Interview line:

    PDB protects application availability during planned maintenance. It ensures Kubernetes does not evict too many replicas of the same service at the same time.

---

## 4.6 Topology Spread Constraints

Topology spread constraints were used to distribute Pods across nodes and availability zones.

Example:

    topologySpreadConstraints:
      - maxSkew: 1
        topologyKey: topology.kubernetes.io/zone
        whenUnsatisfiable: ScheduleAnyway

This helps avoid placing all replicas on the same node or zone.

Good distribution:

    Pod 1 → Node 1 / Zone A
    Pod 2 → Node 2 / Zone B
    Pod 3 → Node 3 / Zone C

Bad distribution:

    Pod 1 → Node 1 / Zone A
    Pod 2 → Node 1 / Zone A
    Pod 3 → Node 1 / Zone A

If one node or zone fails, the application impact is reduced when replicas are spread properly.

Interview line:

    Topology spread constraints helped us reduce the blast radius by spreading replicas across different nodes and availability zones.

---

## 4.7 ALB Health Checks

For external traffic, ALB health checks were configured on service-specific endpoints.

Example:

    /api/products/health
    /api/auth/health
    /api/orders/health

This ensures that the load balancer sends traffic only to healthy targets.

Interview line:

    Kubernetes readiness probes controlled traffic inside the cluster, and ALB health checks controlled traffic from the load balancer side.

---

## High Availability Interview Answer

    High availability was ensured using multiple replicas, rolling update strategy, readiness probes, liveness probes, startup probes, PodDisruptionBudget, and topology spread constraints.

    We did not rely only on replica count. We made sure replicas were distributed across different nodes and zones. Readiness probes ensured only healthy Pods received traffic. PDB protected the application during node drain and upgrades. Rolling updates ensured deployments happened without taking all Pods down together.

---

# 5. How Scalability Was Implemented

Scalability was implemented using Horizontal Pod Autoscaler.

HPA automatically adjusts the number of Pods based on metrics like:

    CPU utilization
    Memory utilization
    Custom metrics
    External metrics

Example:

    apiVersion: autoscaling/v2
    kind: HorizontalPodAutoscaler
    spec:
      minReplicas: 2
      maxReplicas: 10
      metrics:
        - type: Resource
          resource:
            name: cpu
            target:
              type: Utilization
              averageUtilization: 70

        - type: Resource
          resource:
            name: memory
            target:
              type: Utilization
              averageUtilization: 80

Meaning:

    Minimum Pods: 2
    Maximum Pods: 10
    Scale based on average CPU > 70%
    Scale based on average memory > 80%

---

## 5.1 How HPA Calculates Scaling

HPA calculates utilization based on resource requests, not limits.

Example Deployment resources:

    resources:
      requests:
        cpu: 250m
        memory: 512Mi
      limits:
        cpu: 500m
        memory: 1Gi

If actual CPU usage is 200m:

    CPU request = 250m
    Actual CPU = 200m

    Utilization = 200 / 250 * 100 = 80%

If HPA target is 70%, then 80% is above target, so HPA scales up.

---

## 5.2 Multiple Metrics Behavior

If both CPU and memory are configured, HPA calculates desired replicas separately for each metric and chooses the highest recommendation.

Example:

    CPU recommends: 3 replicas
    Memory recommends: 5 replicas

    Final HPA decision: 5 replicas

This ensures the application has enough capacity for all configured resource conditions.

---

## 5.3 Scale-Up and Scale-Down Behavior

Production systems usually scale up fast and scale down slowly.

Example:

    behavior:
      scaleUp:
        stabilizationWindowSeconds: 30
        policies:
          - type: Percent
            value: 100
            periodSeconds: 60
          - type: Pods
            value: 4
            periodSeconds: 60
        selectPolicy: Max

      scaleDown:
        stabilizationWindowSeconds: 300
        policies:
          - type: Percent
            value: 50
            periodSeconds: 60
        selectPolicy: Max

Meaning:

    Scale up quickly when traffic increases.
    Scale down slowly when traffic decreases.

Scale-up:

    Can double Pod count within 60 seconds
    OR
    Can add 4 Pods within 60 seconds
    Whichever is higher

Scale-down:

    Wait 5 minutes before scaling down
    Remove maximum 50% Pods per minute

This avoids instability during temporary traffic drops.

Interview line:

    In production, we generally scale up fast to handle traffic spikes and scale down slowly to avoid instability if traffic comes back suddenly.

---

## 5.4 Node-Level Scalability

HPA only increases Pod count.

But if the cluster does not have enough node capacity, new Pods may remain Pending.

So enterprises also use:

    Cluster Autoscaler
    Karpenter
    EKS managed node group scaling

Flow:

    Traffic increases
       ↓
    HPA increases Pod replicas
       ↓
    Pods need more CPU/memory
       ↓
    If nodes do not have enough capacity, Pods remain Pending
       ↓
    Cluster Autoscaler/Karpenter adds new nodes
       ↓
    Pending Pods get scheduled

Interview line:

    HPA handles application-level scaling, while Cluster Autoscaler or Karpenter handles node-level scaling.

---

## Scalability Interview Answer

    Scalability was implemented using Horizontal Pod Autoscaler. We configured minReplicas and maxReplicas for each service and scaled based on CPU, memory, or custom metrics. Resource requests and limits were defined properly because HPA calculates utilization based on requests.

    For production, we used a scale-up fast and scale-down slow approach. This helped the application respond quickly to traffic spikes but avoided aggressive scale-down during temporary traffic drops. At infrastructure level, HPA was supported by node autoscaling using Cluster Autoscaler or Karpenter so that additional Pods could be scheduled when node capacity was insufficient.

---

# 6. How Security Was Implemented

Security was implemented at multiple layers.

---

## 6.1 Container Security

Containers were configured to run as non-root users.

Example:

    securityContext:
      runAsNonRoot: true
      runAsUser: 10001
      runAsGroup: 10001
      fsGroup: 10001

This prevents the container process from running as root.

Interview line:

    Running containers as non-root reduces the impact if the application process is compromised.

---

## 6.2 Disable Privilege Escalation

Example:

    securityContext:
      allowPrivilegeEscalation: false

This prevents processes inside the container from gaining additional privileges.

Interview line:

    We disabled privilege escalation so that a process inside the container cannot gain higher privileges than it already has.

---

## 6.3 Drop Linux Capabilities

Example:

    capabilities:
      drop:
        - ALL

This removes unnecessary Linux capabilities from the container.

Interview line:

    We dropped all Linux capabilities unless the application explicitly required any capability.

---

## 6.4 Read-Only Root Filesystem

Example:

    readOnlyRootFilesystem: true

This prevents the application from writing to the container root filesystem.

If the application needs temporary storage, mount an emptyDir volume:

    volumeMounts:
      - name: tmp
        mountPath: /tmp

    volumes:
      - name: tmp
        emptyDir: {}

This is useful for Java/Spring Boot applications that may need /tmp.

Interview line:

    We used read-only root filesystem wherever possible and mounted only required writable paths like /tmp.

---

## 6.5 Seccomp Runtime Profile

Example:

    seccompProfile:
      type: RuntimeDefault

This applies the default seccomp profile and restricts unnecessary Linux system calls.

Interview line:

    We used seccomp RuntimeDefault to reduce the system call surface available to the container.

---

## 6.6 Dedicated ServiceAccount

Each application used a dedicated ServiceAccount instead of the default ServiceAccount.

Example:

    serviceAccountName: product-service-sa

This helps with:

    Least privilege
    Workload identity
    Future IRSA/EKS Pod Identity integration
    Better auditability

Interview line:

    We avoided using the default ServiceAccount and gave each workload its own dedicated ServiceAccount.

---

## 6.7 IRSA / EKS Pod Identity

If an application needed AWS access, we did not use static AWS keys inside Pods.

Instead, we used:

    IAM Roles for Service Accounts
    or
    EKS Pod Identity

Example use cases:

    Read from AWS Secrets Manager
    Read/write to S3
    Send messages to SQS
    Publish to SNS
    Access DynamoDB

Production pattern:

    Kubernetes ServiceAccount
       ↓
    Mapped to IAM Role
       ↓
    IAM Role has least-privilege policy
       ↓
    Only Pods using that ServiceAccount can access specific AWS resources

Example:

    product-service-sa can read only:
    arn:aws:secretsmanager:ap-south-1:<account-id>:secret:shopease/development/product-service

Interview line:

    IRSA or EKS Pod Identity allows Pods to access AWS services using IAM roles instead of static access keys.

---

## 6.8 Secret Management

Secrets were not stored directly in Git.

Better production options:

    AWS Secrets Manager
    External Secrets Operator
    Secrets Store CSI Driver
    HashiCorp Vault
    Sealed Secrets
    SOPS

External Secrets Operator flow:

    AWS Secrets Manager
       ↓
    External Secrets Operator
       ↓
    Kubernetes Secret
       ↓
    Deployment consumes Secret

Important point:

    If ESO creates a normal Kubernetes Secret, that Secret is stored in etcd.
    So encryption at rest, RBAC, and audit logging are important.

Interview line:

    We avoided committing plain secrets into Git. Secrets were stored in AWS Secrets Manager or Vault and synced or mounted into Kubernetes using enterprise secret management tools.

---

## 6.9 Encryption at Rest

Kubernetes Secrets should be encrypted at rest.

In EKS, this is handled through KMS envelope encryption.

Flow:

    Kubernetes Secret
       ↓
    API Server encrypts Secret data
       ↓
    Encrypted data stored in etcd

This protects secrets stored in Kubernetes backing store.

Interview line:

    Since Kubernetes Secrets can be stored in etcd, we enabled encryption at rest using KMS and restricted Secret access using RBAC.

---

## 6.10 RBAC

Access was controlled using Kubernetes RBAC.

Production practice:

    Developers should not have cluster-admin access.
    Access should be namespace-scoped.
    Only selected users should access Secrets.
    kubectl exec access should be restricted in production.

Reason:

    If a user can exec into a Pod, they may read environment variables or mounted secrets.

Interview line:

    We followed least privilege using RBAC. Production access was limited, and access to secrets or exec into Pods was restricted.

---

## 6.11 Image Security

Container images were scanned during CI/CD.

Common scanning tools:

    Trivy
    Grype
    Snyk
    Aqua
    Prisma Cloud
    ECR enhanced scanning

Best practices:

    Use minimal base images
    Avoid root user
    Avoid latest tag
    Use immutable image tags
    Scan images before deployment
    Remove unnecessary packages

Interview line:

    Images were scanned before deployment and immutable tags were used for traceability and rollback.

---

## Security Interview Answer

    Security was implemented using a layered approach. At container level, we ran containers as non-root, disabled privilege escalation, dropped Linux capabilities, used read-only root filesystem, and enabled seccomp RuntimeDefault.

    At Kubernetes identity level, each service used a dedicated ServiceAccount instead of the default ServiceAccount. For AWS access, we used IRSA or EKS Pod Identity so that Pods could assume IAM roles with least-privilege permissions instead of using static AWS keys.

    For secrets, we avoided storing plain secrets in Git. In production, secrets were stored in AWS Secrets Manager or Vault and synced using External Secrets Operator or mounted using CSI driver. Since Kubernetes Secrets can be stored in etcd, we enabled encryption at rest using KMS and restricted access using RBAC.

    We also scanned container images in CI/CD and used immutable tags for traceability and rollback.

---

# 7. How Fault Tolerance Was Implemented

Fault tolerance means the system can continue working or recover quickly when something fails.

We handled fault tolerance at different levels.

---

## 7.1 Pod Failure

If a Pod crashes, the Deployment controller automatically creates a replacement Pod.

Example:

    Desired replicas: 2
    Current running Pods: 1

    Deployment controller creates 1 new Pod

Interview line:

    Kubernetes provides self-healing through controllers like Deployment and ReplicaSet.

---

## 7.2 Container Failure

If a container becomes unhealthy, liveness probe restarts it.

Example:

    Application is running but stuck
    Liveness probe fails
    Kubelet restarts container

Interview line:

    Liveness probe helps recover containers that are running but not healthy.

---

## 7.3 Application Not Ready

If an application is not ready to serve traffic, readiness probe fails.

Example:

    Pod is running
    But readiness probe fails
    Service removes Pod from endpoints
    Traffic is not sent to that Pod

Interview line:

    Readiness probe prevents traffic from going to a Pod that is not ready to serve requests.

---

## 7.4 Node Failure

If a worker node fails, Pods on that node are lost.

Kubernetes reschedules replacement Pods on healthy nodes, assuming capacity is available.

Example:

    Node 1 fails
    product-service-pod-1 is lost
    Deployment creates replacement Pod
    Scheduler places it on Node 2 or Node 3

Topology spread helps reduce impact because all replicas should not be on the same node.

Interview line:

    If a node fails, Kubernetes recreates affected Pods on other available nodes, and topology spread reduces the chance that all replicas fail together.

---

## 7.5 Availability Zone Failure

In production, worker nodes should be spread across multiple Availability Zones.

Example:

    Zone A
    Zone B
    Zone C

Pods should also be spread across zones using topology spread constraints or anti-affinity.

If one AZ has issues, replicas in other AZs can continue serving traffic.

Interview line:

    We spread workloads across nodes and availability zones to reduce the impact of node or AZ-level failures.

---

## 7.6 Planned Maintenance

For planned maintenance, such as node drain or node group upgrade, PDB protects availability.

Example:

    Deployment replicas: 3
    PDB maxUnavailable: 1

    During node drain:
    Only 1 Pod can be evicted at a time
    At least 2 Pods remain available

Interview line:

    PDB is important during planned disruptions because it prevents Kubernetes from evicting too many Pods of the same application at once.

---

## 7.7 Load Balancer Health

ALB health checks ensure traffic goes only to healthy targets.

If one Pod or target becomes unhealthy:

    ALB stops sending traffic to it
    Traffic continues to healthy targets

Interview line:

    ALB health checks and Kubernetes readiness probes worked together to ensure only healthy Pods received traffic.

---

## Fault Tolerance Interview Answer

    Fault tolerance was implemented through Kubernetes self-healing and workload design. Deployments maintained the desired number of replicas, so if a Pod failed, Kubernetes recreated it. Liveness probes restarted unhealthy containers, readiness probes removed unhealthy Pods from traffic, and startup probes protected slow-starting applications.

    For node-level failures, replicas were spread across different nodes and zones. If a node failed, Kubernetes rescheduled Pods on healthy nodes. For planned disruptions like node drain and node group upgrades, PDB ensured that not all replicas were evicted at the same time. ALB health checks and Kubernetes readiness probes ensured that traffic was routed only to healthy Pods.

---

# 8. How Production Traffic Was Routed

External traffic was routed using AWS Application Load Balancer through Kubernetes Ingress.

Flow:

    User
       ↓
    Route53 / DNS
       ↓
    AWS Application Load Balancer
       ↓
    Ingress rule
       ↓
    Kubernetes Service
       ↓
    Healthy Pod endpoint
       ↓
    Application container

Example:

    /api/products → product-service
    /api/auth     → auth-service
    /api/orders   → order-service

The ALB listener handled HTTP/HTTPS traffic and forwarded requests based on Ingress path rules.

Each service had health check paths.

Example:

    /api/products/health
    /api/auth/health

This helped ALB identify healthy targets.

---

## Ingress Interview Answer

    We used Ingress with AWS Load Balancer Controller to expose services externally. The ALB handled path-based routing, where different API paths were forwarded to different Kubernetes Services. Each Service then routed traffic to healthy Pod endpoints.

    We configured health check paths for each service so that ALB could verify target health. Kubernetes readiness probes and ALB health checks worked together to ensure traffic reached only healthy application Pods.

---

# 9. How Environment Separation Was Handled

Environment separation was handled using namespaces and environment-specific configuration.

Example:

    school-spider-dev
    school-spider-staging
    school-spider-prod

or:

    iris-dev
    iris-staging
    iris-prod

Each environment had its own:

    Namespace
    ConfigMap
    Secret
    Ingress host/path
    Replica count
    HPA limits
    Resource requests/limits
    ServiceAccount
    RBAC

The same Docker image could be promoted across environments.

Example:

    Dev        → replicas: 1
    Staging    → replicas: 2
    Production → replicas: 3 or more

This allowed consistency while maintaining different capacity and configuration per environment.

In enterprises, this is usually implemented using:

    Helm values
    Kustomize overlays
    GitOps tools like Argo CD or Flux
    CI/CD variable templates

---

## Environment Separation Interview Answer

    We separated environments using namespaces and environment-specific configuration. The same application image was promoted across dev, staging, and production, while ConfigMaps, Secrets, replica counts, resource limits, HPA thresholds, and Ingress configurations were different per environment.

    In production-style setups, this is usually managed using Helm values, Kustomize overlays, or GitOps tools like Argo CD.

---

# 10. How Monitoring and Troubleshooting Was Handled

For Kubernetes troubleshooting, we followed a structured approach.

---

## 10.1 Pod-Level Checks

Commands:

    kubectl get pods -n <namespace>
    kubectl describe pod <pod-name> -n <namespace>
    kubectl logs <pod-name> -n <namespace>

Used for:

    CrashLoopBackOff
    ImagePullBackOff
    OOMKilled
    Probe failures
    Scheduling failures
    Application errors

---

## 10.2 Deployment-Level Checks

Commands:

    kubectl get deployment -n <namespace>
    kubectl describe deployment <deployment-name> -n <namespace>
    kubectl rollout status deployment/<deployment-name> -n <namespace>
    kubectl rollout history deployment/<deployment-name> -n <namespace>

Used for:

    Rollout failures
    Replica mismatch
    Image update issues
    Progress deadline exceeded

---

## 10.3 Service-Level Checks

Commands:

    kubectl get svc -n <namespace>
    kubectl describe svc <service-name> -n <namespace>
    kubectl get endpoints <service-name> -n <namespace>

Used for:

    Service selector mismatch
    No endpoints
    Wrong targetPort
    Traffic not reaching Pods

---

## 10.4 Ingress-Level Checks

Commands:

    kubectl get ingress -n <namespace>
    kubectl describe ingress <ingress-name> -n <namespace>

Used for:

    Ingress rule mismatch
    Wrong service name
    Wrong path
    ALB not created
    ALB target unhealthy

---

## 10.5 Resource Usage Checks

Commands:

    kubectl top pods -n <namespace>
    kubectl top nodes

Used for:

    High CPU usage
    High memory usage
    OOMKilled issues
    HPA scaling issues
    Node pressure

---

# 11. Common Production Issues and Troubleshooting

## 11.1 ImagePullBackOff

Possible causes:

    Wrong image name
    Wrong image tag
    Image not pushed to ECR
    ECR permission issue
    Node IAM role missing ECR read permission
    Private registry secret missing

Commands:

    kubectl describe pod <pod-name> -n <namespace>
    aws ecr describe-images --repository-name <repo-name> --region <region>

Interview answer:

    For ImagePullBackOff, I first check pod events using kubectl describe pod. Usually the event shows whether the issue is wrong image tag, repository not found, authentication failure, or permission issue. Then I verify the image exists in ECR and confirm the worker node role or imagePullSecret has permission to pull the image.

---

## 11.2 CrashLoopBackOff

Possible causes:

    Application startup failure
    Wrong environment variable
    Database connection failure
    Missing Secret/ConfigMap
    Port mismatch
    Application exception

Commands:

    kubectl logs <pod-name> -n <namespace>
    kubectl describe pod <pod-name> -n <namespace>

Interview answer:

    For CrashLoopBackOff, I check container logs first because the application usually crashes due to startup exceptions, missing config, DB connection issues, or wrong environment variables. I also check events, probes, and recent Deployment changes.

---

## 11.3 Pod Pending

Possible causes:

    Insufficient CPU/memory
    Node taints
    Node affinity mismatch
    Topology spread constraints
    PVC not bound
    Cluster autoscaler issue

Command:

    kubectl describe pod <pod-name> -n <namespace>

Interview answer:

    For Pending Pods, I check the Events section using kubectl describe pod. It usually tells whether the issue is insufficient resources, taints, node affinity, topology spread constraints, PVC binding, or autoscaler delay.

---

## 11.4 Service Not Routing Traffic

Possible causes:

    Service selector does not match Pod labels
    Wrong targetPort
    Pods not ready
    No endpoints
    NetworkPolicy blocking traffic

Commands:

    kubectl get endpoints <service-name> -n <namespace>
    kubectl get pods -n <namespace> --show-labels
    kubectl describe svc <service-name> -n <namespace>

Interview answer:

    If Service is not routing traffic, I check whether the Service has endpoints. If endpoints are empty, I verify Service selector and Pod labels. Then I check whether Pods are Ready and whether the targetPort matches the container port.

---

## 11.5 Ingress / ALB Issue

Possible causes:

    Wrong Ingress path
    Wrong service name
    Wrong service port
    ALB controller issue
    Subnet tags missing
    Target group unhealthy
    Security group issue
    Health check path wrong

Commands:

    kubectl describe ingress <ingress-name> -n <namespace>
    kubectl logs -n kube-system deployment/aws-load-balancer-controller

Interview answer:

    For Ingress or ALB issues, I check Ingress rules, annotations, service name, service port, ALB controller logs, target group health, security groups, subnet tags, and health check path.

---

## Monitoring and Troubleshooting Interview Answer

    For troubleshooting, I followed a layered approach. First, I checked Pod status, events, and logs. Then I checked Deployment rollout status, Service selectors, Endpoints, Ingress rules, and ALB target health.

    For resource issues, I checked CPU and memory usage using kubectl top pods and kubectl top nodes. For production monitoring, Kubernetes metrics were integrated with monitoring tools like Prometheus, Grafana, CloudWatch Container Insights, or similar observability platforms.

    My troubleshooting approach was to identify whether the issue was at Pod level, Service level, Ingress/load balancer level, DNS/networking level, resource level, or application level.

---

# 12. Node Drain, Cluster Upgrade, and Node Group Upgrade

## 12.1 Node Drain

Node drain means safely emptying a worker node before maintenance.

Command:

    kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data

Drain does two things:

    1. Cordons the node so new Pods are not scheduled there.
    2. Evicts existing Pods safely from the node.

Flow:

    Node needs maintenance
       ↓
    Node is cordoned
       ↓
    Existing Pods are evicted
       ↓
    Deployment creates replacement Pods
       ↓
    Scheduler places replacement Pods on healthy nodes

PDB is checked during eviction.

If eviction violates the PDB, drain can be blocked.

---

## 12.2 Cluster Upgrade

Cluster upgrade means upgrading the Kubernetes control plane version.

Control plane includes:

    API Server
    Scheduler
    Controller Manager
    etcd

In EKS, AWS manages the control plane.

Example:

    Kubernetes version 1.29 → 1.30

After control plane upgrade, worker nodes and add-ons usually also need to be upgraded.

---

## 12.3 Node Group Upgrade

Node group upgrade means upgrading or replacing worker nodes.

Worker nodes are EC2 instances where Pods actually run.

During EKS managed node group upgrade:

    New node is created
    Old node is cordoned
    Old node is drained
    Pods are rescheduled
    Old node is terminated

PDB helps ensure that not too many Pods are evicted at the same time.

---

## Interview Answer

    Node drain means safely removing Pods from a worker node before maintenance. It cordons the node and evicts Pods using the Kubernetes Eviction API. During eviction, Kubernetes checks PDB to make sure application availability is not violated.

    Cluster upgrade means upgrading the Kubernetes control plane version, such as API Server, scheduler, controller manager, and etcd. In EKS, AWS manages the control plane upgrade.

    Node group upgrade means upgrading or replacing worker nodes where Pods run. During node group upgrades, nodes are drained one by one, Pods are rescheduled on healthy nodes, and old nodes are terminated.

---

# 13. What Happens When We Say Kubernetes Evicts Pods?

When we say Kubernetes evicts Pods, it involves multiple components.

Flow:

    kubectl drain
       ↓
    Eviction request goes to API Server
       ↓
    API Server checks eviction rules and PDB
       ↓
    If allowed, Pod is evicted
       ↓
    Deployment/ReplicaSet controller notices replica count is low
       ↓
    New Pod is created
       ↓
    Scheduler assigns new Pod to another node
       ↓
    Kubelet on that node starts the container

So yes, API Server is the entry point, but the complete flow includes:

    API Server
    PDB / Eviction API
    Deployment controller
    ReplicaSet controller
    Scheduler
    Kubelet

Interview line:

    Kubernetes eviction is not done by only one component. The API Server receives the eviction request, PDB is evaluated, controllers create replacement Pods, scheduler assigns them to nodes, and kubelet runs the containers.

---

# 14. How Enterprises Maintain Replica Count

In enterprises, replica count can be maintained in multiple ways.

## 14.1 Directly in Deployment

Example:

    spec:
      replicas: 3

This means Kubernetes should keep 3 Pods running.

## 14.2 Through Helm Values

Example:

    # values-dev.yaml
    replicaCount: 1

    # values-staging.yaml
    replicaCount: 2

    # values-prod.yaml
    replicaCount: 4

The Deployment template uses:

    replicas: {{ .Values.replicaCount }}

## 14.3 Through Kustomize Overlays

Example:

    base deployment.yaml
    overlays/dev
    overlays/staging
    overlays/prod

Each environment can have a different replica count.

## 14.4 Through HPA

If HPA is configured:

    minReplicas: 2
    maxReplicas: 10

Then HPA dynamically updates the Deployment replica count based on metrics.

## Interview Answer

    In enterprise Kubernetes setups, replica count is usually defined either directly in the Deployment or through Helm/Kustomize environment-specific values. For production workloads, we generally combine Deployment replicas with HPA.

    For example, dev may run one replica, staging may run two replicas, and production may keep minimum two or three replicas. HPA then scales the Deployment based on CPU, memory, or custom metrics within minReplicas and maxReplicas.

    The Deployment controller maintains the desired replica count. If HPA is configured, HPA updates the desired replica count dynamically, and the Deployment controller reconciles the Pods.

---

# 15. PriorityClass and Preemption

PriorityClass tells Kubernetes which Pods are more important when cluster resources are limited.

Example priority levels:

    shopease-critical = 1000000
    shopease-high     = 100000
    shopease-medium   = 10000
    shopease-low      = 100

Use cases:

    critical → auth, gateway, payment
    high     → product, cart, order
    medium   → internal dashboards
    low      → batch jobs, cleanup jobs

If a high-priority Pod cannot be scheduled due to lack of resources, Kubernetes may preempt lower-priority Pods if preemption is allowed.

Example:

    Pending Pod:
    auth-service
    priority: critical

    Running Pod:
    batch-job
    priority: low

    Kubernetes may evict batch-job to make room for auth-service.

Important:

    PriorityClass does not create extra CPU or memory.
    It only decides scheduling preference during resource pressure.

Interview answer:

    PriorityClass was used to classify workloads based on business importance. Critical services were given higher priority, while batch jobs were given lower priority. During resource pressure, Kubernetes scheduler can preempt lower-priority Pods to schedule higher-priority user-facing services.

---

# 16. globalDefault in PriorityClass

Example:

    globalDefault: false

Meaning:

    This PriorityClass will not be automatically applied to all Pods.

A Pod will use this PriorityClass only when explicitly mentioned:

    priorityClassName: shopease-high

Why false is safer:

    We do not want every Pod to automatically become high priority.
    Each workload should intentionally choose its priority.

If globalDefault is true:

    Pods without priorityClassName will automatically get that PriorityClass.

Important:

    Only one PriorityClass in the cluster can have globalDefault: true.

Interview answer:

    globalDefault: false means this PriorityClass is not assigned automatically to all Pods. A Pod gets this priority only if we explicitly define priorityClassName in the Pod spec. This is safer in production because we can intentionally classify workloads as critical, high, medium, or low instead of giving every workload the same priority.

---

# 17. External Secrets Operator

External Secrets Operator, or ESO, is a Kubernetes operator that syncs secrets from external secret stores into Kubernetes.

External stores can be:

    AWS Secrets Manager
    AWS SSM Parameter Store
    HashiCorp Vault
    Azure Key Vault
    Google Secret Manager

Flow:

    AWS Secrets Manager
       ↓
    External Secrets Operator
       ↓
    Kubernetes Secret
       ↓
    Deployment consumes Secret

Example:

    AWS secret name:
    shopease/development/product-service

    Kubernetes Secret created:
    product-service-secret

Important:

    ESO itself does not directly inject secrets into Pods.
    ESO creates or updates Kubernetes Secret.
    Kubernetes injects that Secret into the Pod as env vars or volume.

If used as env vars:

    env | grep SPRING_DATASOURCE_PASSWORD
    echo $SPRING_DATASOURCE_PASSWORD

Yes, someone with exec access can see it.

Therefore enterprises restrict:

    kubectl exec access
    Secret read access
    RBAC permissions
    Production admin access

Interview answer:

    ESO helps avoid storing plain secrets in Git. It syncs secrets from AWS Secrets Manager or Vault into Kubernetes Secrets. However, if ESO creates a normal Kubernetes Secret, that Secret is stored in etcd. So encryption at rest, strict RBAC, and restricted exec access are important.

---

# 18. Encryption at Rest

Kubernetes Secrets are stored in the Kubernetes API backend, usually etcd.

Without encryption at rest:

    Secret data may be stored in etcd in an unencrypted form.

With encryption at rest:

    Kubernetes Secret
       ↓
    API Server encrypts Secret data
       ↓
    Encrypted data is stored in etcd

In EKS, encryption at rest is usually implemented using AWS KMS envelope encryption.

Enterprise practice:

    Enable Kubernetes Secret encryption at rest.
    Use KMS keys.
    Restrict access to KMS key.
    Restrict RBAC access to Secrets.
    Enable audit logging.
    Avoid storing plain Secret YAML in Git.

Interview answer:

    Encryption at rest means Kubernetes Secret data is encrypted before being stored in etcd. In EKS, this can be implemented using KMS envelope encryption. This is important because tools like External Secrets Operator may create normal Kubernetes Secrets, and those Secrets are stored in etcd.

---

# 19. Complete Enterprise-Level Kubernetes Interview Story

Use this as your main answer in interview:

    In the School Spider / IRIS UK project, Kubernetes was used to run containerized backend services in a scalable, highly available, and secure way. Each microservice was deployed using Kubernetes resources such as Deployment, Service, Ingress, ConfigMap, Secret, ServiceAccount, HPA, PDB, and PriorityClass.

    Each service had its own Deployment to manage Pods and rolling updates. The Deployment defined the image version, replica count, health probes, resource requests and limits, security context, service account, and rollout strategy. Services were exposed internally using ClusterIP Services, and external API traffic was routed using Ingress with AWS Application Load Balancer.

    For deployment safety, we used rolling update strategy. We configured maxUnavailable and maxSurge so that new Pods were created gradually and old Pods were removed only after the new Pods became healthy. This helped avoid downtime during application releases. We also maintained rollout history so that we could rollback quickly if a release caused issues.

    High availability was ensured using multiple replicas, readiness probes, liveness probes, startup probes, topology spread constraints, PodDisruptionBudgets, and multi-AZ worker nodes. We made sure that replicas were spread across different nodes and availability zones so that a single node or zone failure would not bring down the complete service.

    PodDisruptionBudget was used to protect services during voluntary disruptions like node drain, EKS node group upgrade, cluster upgrade, and planned maintenance. For example, if a service had three replicas and PDB allowed maxUnavailable as one, Kubernetes would make sure only one Pod could be evicted at a time during maintenance.

    Scalability was handled using Horizontal Pod Autoscaler. HPA was configured with minReplicas and maxReplicas and scaled Pods based on CPU, memory, or custom metrics. Resource requests and limits were defined for each container because HPA uses requests to calculate utilization. At infrastructure level, HPA was supported by Cluster Autoscaler or Karpenter so that when more Pods were needed and node capacity was insufficient, new nodes could be added automatically.

    Security was implemented using a layered approach. Containers were configured to run as non-root users, privilege escalation was disabled, Linux capabilities were dropped, read-only root filesystem was used where possible, and seccomp RuntimeDefault profile was enabled. Each service used a dedicated ServiceAccount instead of the default ServiceAccount.

    For AWS access, we used IRSA or EKS Pod Identity so that specific Pods could assume specific IAM roles with least-privilege permissions. This avoided storing static AWS credentials inside containers. For secrets, we avoided committing plain secrets into Git. Secrets were managed using AWS Secrets Manager or Vault and synced into Kubernetes using External Secrets Operator or mounted using CSI driver. Since Kubernetes Secrets can be stored in etcd, encryption at rest using KMS, strict RBAC, and audit logging were important.

    Fault tolerance was achieved using Kubernetes self-healing capabilities. If a Pod failed, the Deployment controller recreated it. If a container became unhealthy, liveness probe restarted it. If a Pod was not ready, readiness probe removed it from traffic. If a node failed, Kubernetes rescheduled Pods on healthy nodes, assuming capacity was available. PDB and topology spread constraints reduced the impact of planned maintenance and node failures.

    For traffic routing, we used AWS Application Load Balancer through Kubernetes Ingress. The ALB handled path-based routing and forwarded traffic to Kubernetes Services. Services routed traffic only to ready Pod endpoints. ALB health checks and Kubernetes readiness probes together ensured that only healthy Pods received production traffic.

    For troubleshooting, we followed a structured approach. We checked Pod status, events, logs, Deployment rollout status, Service selectors, Endpoints, Ingress rules, ALB target health, DNS, and resource usage. For issues like ImagePullBackOff, CrashLoopBackOff, Pending Pods, unhealthy ALB targets, or service routing failures, we used kubectl get, describe, logs, rollout status, endpoints, and top commands to identify the root cause.

    Overall, Kubernetes helped us achieve controlled deployments, self-healing, autoscaling, high availability, secure workload identity, and better production reliability.

---

# 20. Short Interview Version

Use this when interviewer asks for a brief explanation:

    Kubernetes was implemented to run our containerized services with high availability, scalability, and controlled deployments. Each service had a Deployment for Pod lifecycle, Service for internal routing, Ingress/ALB for external access, ConfigMap and Secret for configuration, HPA for autoscaling, PDB for disruption protection, and ServiceAccount for workload identity.

    HA was ensured using multiple replicas, readiness probes, rolling updates, PDB, and spreading Pods across nodes and zones. Scalability was handled using HPA based on CPU, memory, or custom metrics, supported by node autoscaling. Security was implemented using non-root containers, restricted security contexts, dedicated ServiceAccounts, IRSA/EKS Pod Identity, secret management through AWS Secrets Manager/ESO, RBAC, and image scanning.

    Fault tolerance was achieved through Kubernetes self-healing, liveness/readiness probes, replica management, node rescheduling, and controlled disruptions during upgrades.

---

# 21. Topics to Prepare for AWS DevOps Kubernetes Interview

Prepare these topics properly:

    1. Pod lifecycle
    2. Deployment and ReplicaSet
    3. Rolling update and rollback
    4. maxUnavailable and maxSurge
    5. Readiness, liveness, and startup probes
    6. Service: ClusterIP, NodePort, LoadBalancer
    7. Ingress and AWS ALB Controller
    8. ConfigMap and Secret
    9. External Secrets Operator
    10. AWS Secrets Manager integration
    11. ServiceAccount and RBAC
    12. IRSA / EKS Pod Identity
    13. HPA and metrics-server
    14. Resource requests and limits
    15. Cluster Autoscaler / Karpenter
    16. PodDisruptionBudget
    17. Node drain
    18. Cluster upgrade
    19. Node group upgrade
    20. Topology spread constraints
    21. Pod anti-affinity
    22. PriorityClass and preemption
    23. ImagePullBackOff troubleshooting
    24. CrashLoopBackOff troubleshooting
    25. Pod Pending troubleshooting
    26. Node NotReady troubleshooting
    27. Service not routing traffic
    28. Ingress / ALB target unhealthy troubleshooting
    29. DNS issue inside Pods
    30. Kubernetes security best practices

---

# 22. One Strong Final Statement

    In production Kubernetes, availability is not achieved by only increasing replicas. It is achieved by combining multiple replicas, health probes, rolling updates, PodDisruptionBudgets, topology spread constraints, autoscaling, secure workload identity, secret management, and proper monitoring. That is the approach we followed while deploying and operating services on Kubernetes.
