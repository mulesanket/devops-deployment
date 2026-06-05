# AWS DevOps Kubernetes Interview Preparation - Detailed Answers with Real-Time Examples

## 1. Pod Lifecycle

### What it means

A Pod is the smallest deployable unit in Kubernetes. It contains one or more containers. When we create a Deployment, Kubernetes creates Pods through ReplicaSets.

A Pod goes through multiple phases:

    Pending
    Running
    Succeeded
    Failed
    Unknown

Common container states inside a Pod:

    Waiting
    Running
    Terminated

### Real-time example

Suppose we deploy product-service in Kubernetes.

Flow:

    1. Deployment is applied.
    2. ReplicaSet is created.
    3. Pod is created.
    4. Scheduler assigns the Pod to a worker node.
    5. Kubelet on that node pulls the Docker image.
    6. Container starts.
    7. Startup probe checks whether app started.
    8. Readiness probe checks whether app can receive traffic.
    9. Service sends traffic only after Pod becomes Ready.
    10. If container crashes, kubelet restarts it depending on restartPolicy.

Example command:

    kubectl get pods -n iris-prod

Example output:

    NAME                                  READY   STATUS    RESTARTS
    product-service-7c8d9f6f7b-x2k9m      1/1     Running   0

### What happens internally

    API Server receives Pod object.
    Scheduler selects a node.
    Kubelet on selected node starts the Pod.
    Container runtime pulls image and starts container.
    Kubelet reports status back to API Server.

### Interview answer

    A Pod is the smallest deployable unit in Kubernetes. In our project, application containers were running inside Pods, and those Pods were managed by Deployments. When a Deployment was applied, Kubernetes created a ReplicaSet, and the ReplicaSet created Pods. The Pod initially stayed in Pending state until the scheduler assigned it to a node. Then kubelet pulled the image, started the container, and health probes decided whether the Pod was ready to receive traffic.

    For example, for product-service, once the Pod became Running and readiness probe passed, the Service started routing traffic to it. If the container crashed, kubelet restarted it, and if the Pod was deleted, the Deployment controller created a replacement Pod.

---

## 2. Deployment and ReplicaSet

### What it means

Deployment is a higher-level Kubernetes object used to manage application releases.

Deployment manages ReplicaSets, and ReplicaSets manage Pods.

Flow:

    Deployment
       ↓
    ReplicaSet
       ↓
    Pods

### Real-time example

Suppose we define:

    replicas: 3

Kubernetes maintains 3 Pods.

If one Pod is deleted:

    Desired replicas: 3
    Current replicas: 2
    Deployment/ReplicaSet creates 1 new Pod

### Example YAML

    apiVersion: apps/v1
    kind: Deployment
    metadata:
      name: product-service-deployment
      namespace: iris-prod
    spec:
      replicas: 3
      selector:
        matchLabels:
          app: product-service
      template:
        metadata:
          labels:
            app: product-service
        spec:
          containers:
            - name: product-service
              image: 123456789012.dkr.ecr.ap-south-1.amazonaws.com/product-service:v1

### What ReplicaSet does

ReplicaSet ensures the required number of Pods are running.

Deployment adds extra features like:

    Rolling update
    Rollback
    Revision history
    Declarative updates

### Useful commands

    kubectl get deployment -n iris-prod
    kubectl get rs -n iris-prod
    kubectl get pods -n iris-prod
    kubectl describe deployment product-service-deployment -n iris-prod

### Interview answer

    Deployment is used to manage application rollout and desired state. It creates and manages ReplicaSets, and ReplicaSets maintain the required number of Pods. In our project, each microservice had its own Deployment. For example, product-service had 3 replicas in production. If one Pod failed or was deleted, the ReplicaSet automatically created a replacement Pod.

    Deployment was useful because it provided rolling updates, rollback, revision history, and self-healing for application workloads.

---

## 3. Rolling Update and Rollback

### What it means

Rolling update is a deployment strategy where Kubernetes gradually replaces old Pods with new Pods.

Rollback means reverting to the previous working version if the new version fails.

### Real-time example

Current version:

    product-service:v1
    replicas: 3

New version:

    product-service:v2

Kubernetes does not delete all v1 Pods immediately. It creates v2 Pods gradually and removes v1 Pods only after new Pods become healthy.

### Flow

    1. Existing Pods are running v1.
    2. New image v2 is applied.
    3. Kubernetes creates one new v2 Pod.
    4. Once v2 Pod becomes Ready, one old v1 Pod is terminated.
    5. This continues until all Pods run v2.

### Example YAML

    strategy:
      type: RollingUpdate
      rollingUpdate:
        maxUnavailable: 0
        maxSurge: 1

### Useful commands

    kubectl rollout status deployment/product-service-deployment -n iris-prod
    kubectl rollout history deployment/product-service-deployment -n iris-prod
    kubectl rollout undo deployment/product-service-deployment -n iris-prod

### Real production issue

Suppose new version v2 has wrong DB configuration and Pods go into CrashLoopBackOff.

Then:

    kubectl rollout undo deployment/product-service-deployment -n iris-prod

This rolls back to previous stable ReplicaSet.

### Interview answer

    Rolling update is used to deploy new application versions without downtime. Kubernetes gradually creates new Pods and removes old Pods only after the new Pods become Ready. In our project, we used rolling updates for microservices so users would not face downtime during releases.

    If a new release caused failures like CrashLoopBackOff or readiness probe failures, we used kubectl rollout undo to rollback to the previous stable version. We also checked rollout status and rollout history during deployments.

---

## 4. maxUnavailable and maxSurge

### What it means

maxUnavailable and maxSurge control how many Pods can be unavailable or extra during a rolling update.

Example:

    replicas: 3
    maxUnavailable: 0
    maxSurge: 1

### maxUnavailable

    maxUnavailable: 0

Means Kubernetes should not reduce available Pods during rollout.

If 3 Pods are running, Kubernetes should keep 3 available Pods during deployment.

### maxSurge

    maxSurge: 1

Means Kubernetes can create 1 extra Pod temporarily.

If replicas are 3, during rollout total Pods can become 4 temporarily.

### Real-time example

Before deployment:

    v1 Pod 1
    v1 Pod 2
    v1 Pod 3

During deployment:

    v1 Pod 1
    v1 Pod 2
    v1 Pod 3
    v2 Pod 1   ← extra Pod because maxSurge: 1

After v2 Pod becomes Ready:

    v1 Pod 1 terminated
    v1 Pod 2
    v1 Pod 3
    v2 Pod 1

This continues until all Pods are v2.

### Interview answer

    maxUnavailable controls how many Pods can be unavailable during rollout, and maxSurge controls how many extra Pods can be created temporarily. In production, we used maxUnavailable: 0 and maxSurge: 1 for critical services to avoid reducing availability during deployments.

    For example, if product-service had 3 replicas, Kubernetes could temporarily create a 4th Pod during rollout and only terminate an old Pod after the new Pod became Ready. This helped avoid downtime during release.

---

## 5. Readiness, Liveness, and Startup Probes

### What it means

Kubernetes probes are health checks used to understand application state.

There are three important probes:

    Startup probe
    Readiness probe
    Liveness probe

### Startup probe

Checks whether the application has started successfully.

Useful for slow-starting applications like Java Spring Boot.

Example:

    startupProbe:
      httpGet:
        path: /api/products/health
        port: 8080
      failureThreshold: 30
      periodSeconds: 5

Meaning:

    30 × 5 = 150 seconds startup time allowed

### Readiness probe

Checks whether the Pod is ready to receive traffic.

If readiness fails:

    Pod stays Running
    But Service removes it from endpoints
    Traffic is not sent to it

Example:

    readinessProbe:
      httpGet:
        path: /api/products/health
        port: 8080
      periodSeconds: 10
      failureThreshold: 3

### Liveness probe

Checks whether the container is alive.

If liveness fails repeatedly:

    Kubelet restarts the container

Example:

    livenessProbe:
      httpGet:
        path: /api/products/health
        port: 8080
      initialDelaySeconds: 60
      periodSeconds: 30

### Real-time example

Product-service starts slowly because it loads DB connection and cache.

Without startup probe:

    Liveness probe may fail early
    Kubernetes restarts container repeatedly
    Pod goes into CrashLoopBackOff

With startup probe:

    Kubernetes waits up to 150 seconds
    App starts successfully
    Then readiness/liveness checks begin

### Interview answer

    We used startup, readiness, and liveness probes for application reliability. Startup probe was used for slow-starting services like Java applications. Readiness probe ensured traffic was sent only to Pods that were ready. Liveness probe restarted containers if the application became stuck or unhealthy.

    For example, during deployment, a new Pod was not added to Service endpoints until readiness probe passed. This avoided sending traffic to a half-started application.

---

## 6. Service: ClusterIP, NodePort, LoadBalancer

### What it means

Service provides stable networking for Pods.

Pods are temporary and their IPs can change. Service gives a stable DNS name and virtual IP.

### ClusterIP

Default Service type.

Used for internal communication inside the cluster.

Example:

    product-service.default.svc.cluster.local

Use case:

    auth-service calls product-service internally

### NodePort

Exposes service on every worker node on a high port.

Example:

    NodeIP:30080

Use case:

    Testing or limited external access

Not commonly preferred for production directly.

### LoadBalancer

Creates a cloud load balancer.

In AWS, it can create Classic Load Balancer or Network Load Balancer depending on annotations/controller.

Use case:

    Expose service externally

### Real-time example

In enterprise microservices:

    product-service → ClusterIP
    order-service   → ClusterIP
    auth-service    → ClusterIP
    external access → Ingress + ALB

### Commands

    kubectl get svc -n iris-prod
    kubectl describe svc product-service -n iris-prod

### Interview answer

    Kubernetes Service provides stable networking for Pods. ClusterIP is used for internal service-to-service communication. NodePort exposes the service on each node IP and port, mainly useful for testing. LoadBalancer provisions an external cloud load balancer.

    In production, we mostly used ClusterIP Services behind Ingress. External users accessed the ALB, ALB routed traffic to Ingress rules, Ingress forwarded traffic to ClusterIP Services, and Services forwarded traffic to healthy Pods.

---

## 7. Ingress and AWS ALB Controller

### What it means

Ingress manages external HTTP/HTTPS routing into the cluster.

In AWS EKS, AWS Load Balancer Controller watches Ingress resources and creates an Application Load Balancer.

### Real-time example

Ingress paths:

    /api/auth      → auth-service
    /api/products  → product-service
    /api/orders    → order-service

Flow:

    User
      ↓
    Route53
      ↓
    AWS ALB
      ↓
    Ingress rule
      ↓
    Kubernetes Service
      ↓
    Pod

### Example annotations

    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/healthcheck-path: /api/products/health

### target-type: ip

ALB sends traffic directly to Pod IPs.

### target-type: instance

ALB sends traffic to worker node NodePort.

### Common issues

    ALB not created
    Subnet tags missing
    Wrong ingressClassName
    Service name wrong
    Health check path wrong
    Target group unhealthy

### Commands

    kubectl get ingress -n iris-prod
    kubectl describe ingress product-service-ingress -n iris-prod
    kubectl logs -n kube-system deployment/aws-load-balancer-controller

### Interview answer

    In our EKS setup, we used Ingress with AWS Load Balancer Controller to expose services externally through ALB. The ALB handled path-based routing. For example, /api/products routed to product-service and /api/auth routed to auth-service.

    We used ALB health checks along with Kubernetes readiness probes to ensure only healthy Pods received traffic. While troubleshooting ALB issues, I checked Ingress rules, annotations, service name, service port, target group health, subnet tags, and AWS Load Balancer Controller logs.

---

## 8. ConfigMap and Secret

### ConfigMap

ConfigMap stores non-sensitive configuration.

Examples:

    SPRING_PROFILES_ACTIVE
    LOG_LEVEL
    DATABASE_HOST
    API_BASE_URL

### Secret

Secret stores sensitive configuration.

Examples:

    DB_PASSWORD
    JWT_SECRET
    API_TOKEN
    PRIVATE_KEY

### Real-time example

ConfigMap:

    SPRING_DATASOURCE_URL=jdbc:postgresql://rds-endpoint:5432/iris
    SPRING_DATASOURCE_USERNAME=iris_admin

Secret:

    SPRING_DATASOURCE_PASSWORD=secure-password

Deployment uses both:

    envFrom:
      - configMapRef:
          name: product-service-config
      - secretRef:
          name: product-service-secret

### Important point

Kubernetes Secrets are base64 encoded by default, not encrypted by default in all setups.

Production should use:

    AWS Secrets Manager
    External Secrets Operator
    Encryption at rest
    RBAC restriction
    Audit logging

### Interview answer

    ConfigMap was used for non-sensitive configuration like database URL, profile, log level, and service URLs. Secret was used for sensitive values like passwords, tokens, and keys.

    In production, we avoided storing plain secrets in Git. Instead, secrets were stored in AWS Secrets Manager or Vault and synced into Kubernetes using External Secrets Operator or mounted using CSI driver. We also ensured encryption at rest and restricted access using RBAC.

---

## 9. External Secrets Operator

### What it means

External Secrets Operator, or ESO, syncs secrets from external secret stores into Kubernetes.

External secret stores:

    AWS Secrets Manager
    AWS SSM Parameter Store
    HashiCorp Vault
    Azure Key Vault
    Google Secret Manager

### Flow

    AWS Secrets Manager
       ↓
    External Secrets Operator
       ↓
    Kubernetes Secret
       ↓
    Pod consumes Secret

### Real-time example

AWS Secrets Manager secret:

    shopease/development/product-service

Value:

    {
      "SPRING_DATASOURCE_PASSWORD": "secure-password"
    }

ExternalSecret:

    dataFrom:
      - extract:
          key: shopease/development/product-service

ESO creates Kubernetes Secret:

    product-service-secret

Deployment consumes it:

    envFrom:
      - secretRef:
          name: product-service-secret

### Important security point

ESO does not directly inject secrets into Pods.

ESO creates Kubernetes Secret.

Then Kubernetes injects Secret into Pod as:

    Environment variables
    or
    Mounted files

If injected as env var, someone with exec access can echo it.

Example:

    echo $SPRING_DATASOURCE_PASSWORD

So production must restrict:

    kubectl exec access
    secret read access
    RBAC
    cluster-admin access

### Interview answer

    External Secrets Operator helps avoid storing plain Kubernetes Secret YAML in Git. It fetches secrets from AWS Secrets Manager or Vault and creates Kubernetes Secrets in the target namespace. The application then consumes those Secrets as environment variables or mounted files.

    However, if ESO creates a normal Kubernetes Secret, it is stored in etcd, so encryption at rest, RBAC, and audit logging are required. Also, exec access to production Pods should be restricted because environment variables can be viewed from inside the container.

---

## 10. AWS Secrets Manager Integration

### What it means

AWS Secrets Manager is used to centrally store and rotate secrets.

In EKS, applications can access Secrets Manager in two ways:

    1. Application directly calls Secrets Manager using IAM role
    2. ESO/CSI driver fetches secret and provides it to Kubernetes

### Real-time example

Secret stored in AWS Secrets Manager:

    iris/prod/product-service

Values:

    DB_PASSWORD
    JWT_SECRET
    API_KEY

ESO syncs it into Kubernetes Secret:

    product-service-secret

Application gets:

    SPRING_DATASOURCE_PASSWORD

### IAM permission required

ESO or Pod IAM role needs:

    secretsmanager:GetSecretValue
    secretsmanager:DescribeSecret

Least privilege example:

    Allow access only to:
    arn:aws:secretsmanager:ap-south-1:<account-id>:secret:iris/prod/product-service-*

### Interview answer

    AWS Secrets Manager was used to centrally store sensitive values like database password and API keys. In Kubernetes, we integrated it using External Secrets Operator or CSI driver. ESO used an IAM role with least-privilege permission to read only required secret paths and created Kubernetes Secrets in the namespace.

    This approach avoided storing secrets in Git and made secret rotation easier. We also combined it with encryption at rest, RBAC, and restricted exec access.

---

## 11. ServiceAccount and RBAC

### ServiceAccount

ServiceAccount provides identity to Pods.

Example:

    product-service-sa

Deployment:

    serviceAccountName: product-service-sa

### RBAC

RBAC controls what users or ServiceAccounts can do in Kubernetes.

RBAC objects:

    Role
    ClusterRole
    RoleBinding
    ClusterRoleBinding

### Real-time example

Suppose product-service does not need Kubernetes API access.

Then:

    automountServiceAccountToken: false

If it needs access to specific resources, create Role and RoleBinding.

Example Role:

    Allow read ConfigMaps only in iris-prod namespace.

### Production practice

    Avoid default ServiceAccount
    Use dedicated ServiceAccount per workload
    Give namespace-level access
    Avoid cluster-admin
    Restrict secrets access
    Restrict exec access in production

### Interview answer

    We used dedicated ServiceAccounts for each application instead of using the default ServiceAccount. This improved identity separation and made it easier to apply least privilege.

    RBAC was used to control what users and service accounts could do in the cluster. For example, developers may get read access in production but not permission to delete resources or read Secrets. Access to Secrets and kubectl exec was restricted because it can expose sensitive data.

---

## 12. IRSA / EKS Pod Identity

### What it means

IRSA and EKS Pod Identity allow Kubernetes Pods to access AWS services using IAM roles.

This avoids static AWS access keys inside Pods.

### IRSA

IRSA means IAM Roles for Service Accounts.

Flow:

    Kubernetes ServiceAccount
       ↓
    IAM role annotation
       ↓
    Pod assumes IAM role
       ↓
    Pod accesses AWS service

Example annotation:

    eks.amazonaws.com/role-arn: arn:aws:iam::<account-id>:role/product-service-role

### EKS Pod Identity

EKS Pod Identity is a newer AWS-supported way to associate IAM roles with Kubernetes service accounts.

### Real-time example

product-service needs to read from S3 bucket:

    iris-prod-product-files

Create IAM role with policy:

    s3:GetObject
    s3:ListBucket

Associate that role with product-service-sa.

Only Pods using product-service-sa can access that S3 bucket.

### Interview answer

    IRSA or EKS Pod Identity was used to give AWS permissions to Pods without storing AWS keys inside containers. Each application had its own Kubernetes ServiceAccount, and if that application needed AWS access, the ServiceAccount was mapped to a specific IAM role.

    For example, if product-service needed access to AWS Secrets Manager, only product-service-sa was allowed to assume an IAM role with secretsmanager:GetSecretValue permission for that specific secret path. This followed least privilege and improved security.

---

## 13. HPA and metrics-server

### What it means

HPA means Horizontal Pod Autoscaler.

It automatically scales the number of Pods based on metrics.

metrics-server provides CPU and memory metrics to Kubernetes.

### Real-time example

HPA config:

    minReplicas: 2
    maxReplicas: 10
    CPU target: 70%
    Memory target: 80%

If product-service CPU average crosses 70%, HPA increases replicas.

Example:

    Current Pods: 2
    CPU target: 70%
    Current CPU average: 100%

Formula:

    desired replicas = current replicas × current utilization / target utilization

    desired replicas = 2 × 100 / 70 = 2.85

Rounded up:

    3 Pods

### Commands

    kubectl get hpa -n iris-prod
    kubectl describe hpa product-service-hpa -n iris-prod
    kubectl top pods -n iris-prod
    kubectl top nodes

### Important point

HPA calculates utilization based on resource requests.

If requests are missing, CPU/memory percentage-based HPA may not work properly.

### Interview answer

    HPA was used to scale application Pods based on CPU, memory, or custom metrics. metrics-server collected resource metrics and HPA used those metrics to decide desired replica count.

    For example, product-service had minReplicas 2 and maxReplicas 10. If average CPU utilization crossed 70%, HPA increased Pods. If traffic reduced, HPA scaled down gradually. We ensured resource requests were defined because HPA calculates utilization based on requests.

---

## 14. Resource Requests and Limits

### What it means

Requests define minimum guaranteed resources.

Limits define maximum allowed resources.

Example:

    resources:
      requests:
        cpu: 250m
        memory: 512Mi
      limits:
        cpu: 500m
        memory: 1Gi

### CPU

    250m = 0.25 CPU core
    500m = 0.5 CPU core

### Memory

    512Mi request
    1Gi limit

### Real-time example

If product-service requests 512Mi and uses 1.2Gi while limit is 1Gi:

    Container may be OOMKilled

If CPU usage exceeds limit:

    CPU is throttled

### Why requests are important

Scheduler uses requests to place Pods on nodes.

HPA uses requests to calculate utilization.

Example:

    CPU usage = 200m
    CPU request = 250m
    Utilization = 80%

### Interview answer

    Resource requests and limits were defined for every container. Requests helped scheduler decide where to place Pods and provided a baseline for HPA calculation. Limits protected nodes from one container consuming too many resources.

    For example, if product-service requested 250m CPU and 512Mi memory, Kubernetes scheduled it only on nodes with enough available resources. If memory usage crossed the limit, the container could be OOMKilled, and if CPU crossed the limit, it could be throttled.

---

## 15. Cluster Autoscaler / Karpenter

### What it means

HPA scales Pods.

Cluster Autoscaler or Karpenter scales nodes.

### Problem

HPA increases Pods from 2 to 8.

But cluster has no free CPU/memory.

New Pods stay Pending.

### Solution

Cluster Autoscaler or Karpenter adds new worker nodes.

Flow:

    Traffic increases
       ↓
    HPA increases replicas
       ↓
    New Pods are Pending due to insufficient resources
       ↓
    Cluster Autoscaler/Karpenter detects Pending Pods
       ↓
    New nodes are created
       ↓
    Pods get scheduled

### Cluster Autoscaler

Works with node groups.

Adds or removes nodes from existing node groups.

### Karpenter

Can provision nodes more dynamically based on Pod requirements.

### Interview answer

    HPA handles application-level scaling by increasing or decreasing Pods. But if the cluster does not have enough capacity, new Pods remain Pending. To solve this, node autoscaling is required.

    In EKS, this can be handled using Cluster Autoscaler or Karpenter. Cluster Autoscaler scales existing node groups, while Karpenter can provision nodes dynamically based on Pod requirements. This combination helps scale both Pods and infrastructure.

---

## 16. PodDisruptionBudget

### What it means

PDB protects applications during voluntary disruptions.

Voluntary disruptions:

    kubectl drain
    Node group upgrade
    Cluster upgrade
    Cluster autoscaler scale-down
    Planned maintenance

### Example

Deployment:

    replicas: 3

PDB:

    maxUnavailable: 1

Meaning:

    At most 1 Pod can be voluntarily unavailable.
    At least 2 Pods should remain available.

### Real-time example

product-service has 3 Pods:

    Pod 1 on Node A
    Pod 2 on Node B
    Pod 3 on Node C

During node drain of Node A:

    Kubernetes checks PDB
    PDB allows 1 Pod eviction
    Pod 1 is evicted
    Pod 2 and Pod 3 continue serving traffic
    Replacement Pod is created on another node

### Interview answer

    PodDisruptionBudget was used to maintain availability during planned disruptions. For example, if product-service had 3 replicas and PDB maxUnavailable was 1, Kubernetes allowed only one Pod to be evicted during node drain or node group upgrade.

    PDB does not protect against sudden failures like node crash, but it protects during controlled operations like maintenance and upgrades.

---

## 17. Node Drain

### What it means

Node drain means safely removing Pods from a node before maintenance.

Command:

    kubectl drain <node-name> --ignore-daemonsets --delete-emptydir-data

Drain does:

    1. Cordons the node
    2. Evicts Pods safely

### Real-time example

Node A has:

    product-service-pod
    auth-service-pod
    cart-service-pod

Before node upgrade:

    Node A is drained

Kubernetes evicts Pods and creates replacements on other nodes.

### Role of PDB

Before eviction, Kubernetes checks PDB.

If PDB would be violated:

    Drain is blocked

### Interview answer

    Node drain is used before node maintenance or replacement. It marks the node unschedulable and safely evicts existing Pods. During eviction, Kubernetes checks PDB to ensure too many replicas are not removed at once.

    For example, during EKS node group upgrade, nodes are drained one by one, Pods are rescheduled to healthy nodes, and then old nodes are terminated.

---

## 18. Cluster Upgrade

### What it means

Cluster upgrade means upgrading Kubernetes control plane version.

Control plane includes:

    API Server
    Scheduler
    Controller Manager
    etcd

In EKS, AWS manages the control plane.

### Real-time example

Upgrade:

    Kubernetes 1.29 → 1.30

After control plane upgrade, we also need to update:

    Worker nodes
    CoreDNS
    kube-proxy
    VPC CNI
    Other add-ons

### Production steps

    Check deprecated APIs
    Backup critical configs
    Upgrade dev first
    Upgrade staging
    Validate workloads
    Upgrade production
    Upgrade managed node groups
    Upgrade add-ons
    Monitor workloads

### Interview answer

    Cluster upgrade means upgrading the Kubernetes control plane version. In EKS, AWS manages the control plane upgrade. After upgrading the control plane, we also upgrade worker nodes and Kubernetes add-ons like CoreDNS, kube-proxy, and VPC CNI.

    In production, we first validate in lower environments, check deprecated APIs, monitor workloads, and then proceed with production upgrade.

---

## 19. Node Group Upgrade

### What it means

Node group upgrade means upgrading or replacing worker nodes.

Worker nodes are EC2 instances where Pods run.

### Real-time example

Current node group:

    Kubernetes 1.29 AMI

New node group version:

    Kubernetes 1.30 AMI

Upgrade flow:

    1. New node is created.
    2. Old node is cordoned.
    3. Old node is drained.
    4. Pods are rescheduled.
    5. Old node is terminated.

### Role of PDB

PDB ensures not too many Pods are evicted during node group upgrade.

### Interview answer

    Node group upgrade means upgrading worker nodes in EKS. During managed node group upgrade, EKS creates new nodes, drains old nodes, reschedules Pods, and terminates old nodes.

    PDB is important during node group upgrades because it prevents all replicas of a service from being evicted at the same time.

---

## 20. Topology Spread Constraints

### What it means

Topology spread constraints spread Pods across failure domains like nodes and zones.

Topology keys:

    kubernetes.io/hostname
    topology.kubernetes.io/zone

### Real-time example

product-service has 3 replicas.

Good distribution:

    Pod 1 → Node A / Zone 1
    Pod 2 → Node B / Zone 2
    Pod 3 → Node C / Zone 3

Bad distribution:

    Pod 1 → Node A / Zone 1
    Pod 2 → Node A / Zone 1
    Pod 3 → Node A / Zone 1

### Example

    topologySpreadConstraints:
      - maxSkew: 1
        topologyKey: topology.kubernetes.io/zone
        whenUnsatisfiable: ScheduleAnyway
        labelSelector:
          matchLabels:
            app: product-service

### Meaning

    maxSkew: 1

Difference between zones should not be more than 1.

### Interview answer

    Topology spread constraints were used to distribute replicas across nodes and availability zones. This reduced the risk of all replicas running on the same node or zone.

    For example, if product-service had 3 replicas, topology spread helped place them across different zones. So if one node or zone failed, other replicas could continue serving traffic.

---

## 21. Pod Anti-Affinity

### What it means

Pod anti-affinity tells Kubernetes not to place similar Pods together.

### Real-time example

We do not want all product-service Pods on same node.

Anti-affinity says:

    Do not schedule product-service Pod on a node that already has another product-service Pod.

### Example

    affinity:
      podAntiAffinity:
        preferredDuringSchedulingIgnoredDuringExecution:
          - weight: 100
            podAffinityTerm:
              labelSelector:
                matchLabels:
                  app: product-service
              topologyKey: kubernetes.io/hostname

### preferred vs required

preferred:

    Try to follow the rule, but schedule anyway if not possible.

required:

    Must follow the rule, otherwise Pod remains Pending.

### Interview answer

    Pod anti-affinity was used to avoid placing multiple replicas of the same application on the same node. This improved availability because a single node failure would not take down all replicas.

    In production, we usually use preferred anti-affinity or topology spread constraints so that scheduling does not get blocked unnecessarily.

---

## 22. PriorityClass and Preemption

### What it means

PriorityClass defines workload importance.

Example:

    critical = auth-service
    high     = product/cart/order services
    medium   = internal dashboards
    low      = batch jobs

### Real-time example

Cluster has low resources.

Pending Pod:

    auth-service
    priority: critical

Running Pod:

    batch-job
    priority: low

Kubernetes may evict low-priority Pod to schedule auth-service.

### Example PriorityClass

    apiVersion: scheduling.k8s.io/v1
    kind: PriorityClass
    metadata:
      name: iris-critical
    value: 1000000
    globalDefault: false
    preemptionPolicy: PreemptLowerPriority

### Important point

PriorityClass does not create CPU or memory.

It only influences scheduling priority.

### Interview answer

    PriorityClass was used to classify workloads based on business importance. Critical services like auth or payment were given higher priority, while batch jobs were given low priority.

    During resource pressure, Kubernetes scheduler can preempt lower-priority Pods to make room for higher-priority Pods. This helps ensure important user-facing services get scheduling preference.

---

## 23. ImagePullBackOff Troubleshooting

### What it means

ImagePullBackOff means Kubernetes cannot pull the container image.

### Common causes

    Wrong image name
    Wrong image tag
    Image not pushed to registry
    Private registry authentication issue
    ECR permission issue
    Node cannot reach registry
    ImagePullSecret missing

### Real-time example

Deployment image:

    123456789012.dkr.ecr.ap-south-1.amazonaws.com/product-service:v2

But ECR has only:

    v1

Pod status:

    ImagePullBackOff

### Troubleshooting commands

    kubectl describe pod <pod-name> -n iris-prod

Check Events section.

Possible event:

    manifest unknown
    repository does not exist
    no basic auth credentials
    pull access denied

Check ECR:

    aws ecr describe-images --repository-name product-service --region ap-south-1

Check node IAM role:

    AmazonEC2ContainerRegistryReadOnly

### Interview answer

    For ImagePullBackOff, I first check Pod events using kubectl describe pod. The Events section usually shows whether the issue is wrong image tag, repository not found, authentication failure, or permission issue.

    Then I verify the image exists in ECR, check image tag, confirm the registry URL and region, and validate that the node IAM role or imagePullSecret has permission to pull the image.

---

## 24. CrashLoopBackOff Troubleshooting

### What it means

CrashLoopBackOff means container starts, crashes, Kubernetes restarts it, and this repeats.

### Common causes

    Application startup error
    Missing environment variable
    Wrong database credentials
    Database unreachable
    Port mismatch
    Wrong command/entrypoint
    Memory issue
    ConfigMap/Secret missing
    Liveness probe too aggressive

### Real-time example

product-service requires:

    SPRING_DATASOURCE_PASSWORD

But Secret is missing.

Application starts and crashes:

    Failed to connect to database

Pod enters CrashLoopBackOff.

### Commands

    kubectl logs <pod-name> -n iris-prod
    kubectl logs <pod-name> -n iris-prod --previous
    kubectl describe pod <pod-name> -n iris-prod
    kubectl get events -n iris-prod --sort-by='.lastTimestamp'

### Interview answer

    For CrashLoopBackOff, I first check logs using kubectl logs. If the container restarted, I also use kubectl logs --previous. Then I check Pod events using kubectl describe pod.

    Common causes are application startup errors, missing ConfigMap or Secret, wrong environment variable, DB connection failure, port mismatch, or probe misconfiguration. Once the root cause is fixed, the Deployment creates healthy Pods.

---

## 25. Pod Pending Troubleshooting

### What it means

Pod Pending means Pod is created but not scheduled or not started.

### Common causes

    Insufficient CPU/memory
    Node taints
    Missing tolerations
    Node affinity mismatch
    Topology spread constraints
    PVC not bound
    Image pull not started yet
    Cluster Autoscaler delay

### Real-time example

Pod requests:

    cpu: 4
    memory: 8Gi

But no node has enough available capacity.

Pod remains Pending.

### Command

    kubectl describe pod <pod-name> -n iris-prod

Check Events:

    0/3 nodes are available: insufficient cpu

### Troubleshooting

    Check resource requests
    Check node capacity
    Check taints/tolerations
    Check node affinity
    Check topology spread
    Check PVC
    Check autoscaler logs

### Interview answer

    For Pending Pods, I use kubectl describe pod and check Events. It usually tells why the scheduler could not place the Pod. Common reasons are insufficient CPU or memory, node taints, missing tolerations, node affinity mismatch, topology spread constraints, or PVC not bound.

    If the issue is capacity, HPA may have created more Pods but Cluster Autoscaler or Karpenter needs to add more nodes.

---

## 26. Node NotReady Troubleshooting

### What it means

Node NotReady means Kubernetes control plane is not getting healthy status from kubelet on the worker node.

### Common causes

    Kubelet issue
    Node resource pressure
    Disk pressure
    Memory pressure
    Network issue
    EC2 instance issue
    Container runtime issue
    CNI problem
    IAM/network connectivity issue

### Commands

    kubectl get nodes
    kubectl describe node <node-name>
    kubectl get pods -A -o wide | grep <node-name>

On node:

    systemctl status kubelet
    journalctl -u kubelet
    df -h
    free -m
    top

### Real-time example

Node shows:

    NotReady
    DiskPressure=True

Reason:

    Container logs filled disk

Fix:

    Clean disk
    Rotate logs
    Replace node
    Check daemonsets

### Interview answer

    For Node NotReady, I first check kubectl describe node to see conditions like MemoryPressure, DiskPressure, PIDPressure, or NetworkUnavailable. Then I check kubelet status, container runtime, disk, memory, and network connectivity.

    In EKS, if the node is unhealthy, we may cordon and drain it if possible, or replace it through the managed node group.

---

## 27. Service Not Routing Traffic

### What it means

Service exists, but traffic is not reaching Pods.

### Common causes

    Service selector mismatch
    Pod labels mismatch
    Pods not Ready
    Wrong targetPort
    No endpoints
    NetworkPolicy blocking traffic
    Application not listening on expected port

### Real-time example

Service selector:

    app: product-service

Pod label:

    app: product-service-deployment

Mismatch causes:

    No endpoints

Command:

    kubectl get endpoints product-service -n iris-prod

Output:

    <none>

### Troubleshooting commands

    kubectl get svc product-service -n iris-prod -o yaml
    kubectl get pods -n iris-prod --show-labels
    kubectl get endpoints product-service -n iris-prod
    kubectl describe svc product-service -n iris-prod

### Interview answer

    If Service is not routing traffic, I first check whether the Service has endpoints. If endpoints are empty, I compare Service selector with Pod labels. Then I check whether Pods are Ready and whether targetPort matches the container port.

    Most Service routing issues are caused by selector mismatch, wrong targetPort, or readiness probe failure.

---

## 28. Ingress / ALB Target Unhealthy Troubleshooting

### What it means

ALB is created but targets are unhealthy, so users get 502, 503, or 504.

### Common causes

    Wrong health check path
    Wrong service port
    Wrong targetPort
    Pod readiness failure
    App not listening on expected port
    Security group issue
    Network ACL issue
    Service has no endpoints
    Ingress annotation issue
    ALB target-type mismatch

### Real-time example

ALB health check path:

    /api/products/health

But application exposes:

    /actuator/health

ALB gets 404 and marks targets unhealthy.

### Commands

    kubectl describe ingress product-service-ingress -n iris-prod
    kubectl get svc product-service -n iris-prod
    kubectl get endpoints product-service -n iris-prod
    kubectl describe pod <pod-name> -n iris-prod
    kubectl logs -n kube-system deployment/aws-load-balancer-controller

AWS side:

    Check target group health
    Check health check path
    Check security groups
    Check listener rules

### Interview answer

    For ALB target unhealthy issues, I check both Kubernetes and AWS sides. In Kubernetes, I verify Ingress rules, service name, service port, endpoints, Pod readiness, and application health endpoint. In AWS, I check target group health, health check path, listener rules, and security groups.

    A common issue is health check path mismatch. If ALB checks /api/products/health but the app exposes /actuator/health, targets become unhealthy.

---

## 29. DNS Issue Inside Pods

### What it means

Pods cannot resolve service names or external domains.

### Common symptoms

    curl product-service fails
    nslookup product-service fails
    app cannot connect to internal service
    database hostname not resolving

### Common causes

    CoreDNS issue
    Wrong service name
    Wrong namespace
    NetworkPolicy
    DNS config issue
    CNI issue
    Node network issue

### Real-time example

order-service calls:

    http://product-service:80

But product-service is in another namespace:

    iris-prod

Correct DNS:

    product-service.iris-prod.svc.cluster.local

### Commands

Run temporary debug Pod:

    kubectl run dns-test --image=busybox:1.28 --rm -it -- nslookup product-service.iris-prod.svc.cluster.local

Check CoreDNS:

    kubectl get pods -n kube-system -l k8s-app=kube-dns
    kubectl logs -n kube-system -l k8s-app=kube-dns

Check service:

    kubectl get svc -n iris-prod

### Interview answer

    For DNS issues inside Pods, I first verify the correct service DNS name and namespace. Then I test resolution using a debug Pod with nslookup. I also check whether CoreDNS Pods are running in kube-system namespace and review CoreDNS logs.

    Many DNS issues happen due to wrong namespace usage. For example, product-service can be resolved as product-service only from the same namespace, but from another namespace we should use product-service.iris-prod.svc.cluster.local.

---

## 30. Kubernetes Security Best Practices

### Main areas

Kubernetes security should be implemented in layers:

    Container security
    Pod security
    RBAC
    Network security
    Secret management
    Image security
    Workload identity
    Audit logging
    Runtime security

---

## 30.1 Container Security

Best practices:

    Run as non-root
    Disable privilege escalation
    Drop Linux capabilities
    Use read-only root filesystem
    Use seccomp RuntimeDefault
    Avoid privileged containers

Example:

    securityContext:
      runAsNonRoot: true
      runAsUser: 10001
      allowPrivilegeEscalation: false
      readOnlyRootFilesystem: true
      capabilities:
        drop:
          - ALL
      seccompProfile:
        type: RuntimeDefault

### Interview line

    We hardened containers by running them as non-root, disabling privilege escalation, dropping capabilities, using read-only root filesystem, and applying seccomp RuntimeDefault.

---

## 30.2 RBAC

Best practices:

    Avoid cluster-admin for normal users
    Use namespace-level access
    Restrict Secrets access
    Restrict exec access in production
    Use separate roles for dev, support, and admin teams

### Interview line

    We followed least privilege using RBAC. Developers had limited namespace-level access, while production secret access and exec access were restricted.

---

## 30.3 Secret Security

Best practices:

    Do not commit plain Secret YAML to Git
    Use AWS Secrets Manager or Vault
    Use ESO or CSI driver
    Enable encryption at rest
    Restrict who can read Secrets
    Rotate secrets

### Interview line

    Secrets were managed through AWS Secrets Manager or Vault, and not committed directly to Git. Kubernetes Secrets were protected using encryption at rest and RBAC.

---

## 30.4 Image Security

Best practices:

    Use minimal base images
    Avoid latest tag
    Use immutable tags
    Scan images using Trivy/Snyk/ECR scanning
    Remove unnecessary packages
    Run container as non-root

### Interview line

    Images were scanned in CI/CD before deployment, and immutable image tags like Git SHA or release version were used for traceability and rollback.

---

## 30.5 Network Security

Best practices:

    Use NetworkPolicies
    Restrict namespace-to-namespace communication
    Allow only required traffic
    Secure Ingress using TLS
    Restrict security groups

### Interview line

    Network access was restricted using security groups and, where applicable, Kubernetes NetworkPolicies. Only required service-to-service traffic was allowed.

---

## 30.6 Workload Identity

Best practices:

    Use dedicated ServiceAccount per application
    Use IRSA or EKS Pod Identity for AWS access
    Avoid static AWS keys
    Grant least-privilege IAM policies

### Interview line

    For AWS access, workloads used IRSA or EKS Pod Identity. This allowed Pods to access AWS services using IAM roles instead of static credentials.

---

## Final Security Interview Answer

    Kubernetes security was implemented using a layered approach. At container level, we ran containers as non-root, disabled privilege escalation, dropped Linux capabilities, used read-only root filesystem, and enabled seccomp RuntimeDefault.

    At access level, we used RBAC and restricted production access, especially access to Secrets and kubectl exec. Each application used a dedicated ServiceAccount, and AWS access was provided using IRSA or EKS Pod Identity with least-privilege IAM policies.

    For secrets, we avoided plain secrets in Git. Secrets were stored in AWS Secrets Manager or Vault and synced using External Secrets Operator or mounted using CSI driver. Kubernetes Secrets were protected using encryption at rest and RBAC.

    At image level, images were scanned during CI/CD, minimal base images were used, and immutable tags were followed for traceability and rollback.

---

# Final Combined Interview Answer

    In the project, Kubernetes was used to run containerized microservices in a production-ready way. Each service had a Deployment for lifecycle management, Service for stable networking, Ingress with ALB for external routing, ConfigMap and Secret for configuration, HPA for autoscaling, PDB for disruption protection, and ServiceAccount for workload identity.

    High availability was ensured using multiple replicas, readiness/liveness/startup probes, rolling updates, topology spread constraints, PodDisruptionBudgets, and multi-AZ worker nodes. Scalability was implemented using HPA based on CPU and memory metrics, supported by Cluster Autoscaler or Karpenter for node-level scaling.

    Security was implemented using non-root containers, restricted security contexts, RBAC, dedicated ServiceAccounts, IRSA/EKS Pod Identity, external secret management, image scanning, and encryption at rest.

    For troubleshooting, I followed a layered approach. I checked Pod status, events, logs, Deployment rollout status, Service endpoints, Ingress rules, ALB target health, DNS, node conditions, and resource usage. This helped identify whether the issue was at Pod, Service, Ingress, node, networking, resource, or application level.
