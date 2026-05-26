# Common AWS Production Troubleshooting Approach

## 1. Understand the exact issue first

Before checking AWS services, first confirm:

- What is not working?
- Who is impacted?
- Since when issue started?
- Is it complete outage or partial outage?
- Is it happening for all users or few users?
- Was there any recent change/deployment?

Example questions:

- Is website not opening?
- Is only API failing?
- Is SSH failing?
- Is database connection failing?
- Is only one region/account affected?
- Did we recently change security group, route table, IAM, DNS, or deployment?

---

## 2. Follow the traffic/request path

Always troubleshoot from outside to inside.

For application issue:

User
→ DNS / Route 53
→ CloudFront / WAF
→ Load Balancer
→ Target Group
→ EC2 / ECS / EKS
→ Application
→ Database / Cache / S3 / External API

This helps identify where the request is failing.

---

## 3. Check DNS first

For public application issues, check DNS resolution.

Commands:

nslookup app.example.com
dig app.example.com

Check:

- Route 53 record exists
- Record points to correct ALB/CloudFront/IP
- Public hosted zone is used
- Domain delegation/name servers are correct
- TTL/cache issue
- Health check/failover routing issue

Common solution:

- Fix Route 53 record
- Point DNS to correct ALB/CloudFront
- Correct name server delegation
- Wait for TTL/cache expiry if recently changed

---

## 4. Check Load Balancer

If application is behind ALB/NLB, check:

- ALB is active
- Listener exists on 80/443
- Listener rule forwards to correct target group
- SSL certificate is valid
- Target group has registered targets
- Targets are healthy

Important ALB metrics:

- RequestCount
- TargetResponseTime
- HTTPCode_ELB_5XX_Count
- HTTPCode_Target_5XX_Count
- HealthyHostCount
- UnHealthyHostCount

Common solution:

- Fix listener rule
- Attach correct target group
- Fix SSL certificate
- Register targets
- Fix unhealthy backend instances

---

## 5. Check Target Group health

Target group health tells whether ALB can reach backend.

Check health reason:

- Request timed out
- Connection refused
- Response code mismatch
- Health checks failed

Check:

- Correct health check path
- Correct health check port
- Correct success code
- Application is running
- Security group allows ALB to app

Commands on instance:

curl localhost:8080/health
sudo ss -tulnp | grep 8080

Common solution:

- Correct health check path
- Start/restart application
- Open app port from ALB security group
- Increase health check timeout/grace period
- Fix application startup issue

---

## 6. Check Security Groups

Security group issues are very common.

Typical production flow:

Internet → ALB SG → App SG → DB SG

Correct pattern:

ALB SG:
- Inbound 80/443 from 0.0.0.0/0

App SG:
- Inbound app port from ALB SG

RDS SG:
- Inbound DB port from App SG

Example:

ALB SG:
80, 443 from 0.0.0.0/0

App SG:
8080 from ALB-SG

RDS SG:
3306 from App-SG

Common solution:

- Restore missing SG rule
- Use SG reference instead of public IP
- Avoid opening DB/app directly to internet
- Check CloudTrail for recent SG changes

---

## 7. Check NACL

NACL is stateless, so both inbound and outbound must allow traffic.

Check:

- Application port
- DB port
- Ephemeral ports 1024-65535
- Inbound and outbound rules

Common solution:

- Allow required port both ways
- Allow ephemeral return ports
- Avoid overly restrictive NACL unless required

---

## 8. Check Route Tables and Subnets

For public subnet:

0.0.0.0/0 → Internet Gateway

For private subnet outbound internet:

0.0.0.0/0 → NAT Gateway

Check:

- ALB should be in public subnet for internet-facing app
- EC2/App can be in private subnet
- NAT Gateway should be in public subnet
- NAT public subnet should have route to Internet Gateway

Common solution:

- Add missing IGW route for public subnet
- Add NAT route for private subnet
- Ensure NAT Gateway is available
- Ensure subnet has available IPs

---

## 9. Check EC2 instance health

Check EC2:

- Instance state
- System status check
- Instance status check
- CPU
- Memory
- Disk
- Network

Commands:

uptime
top
free -m
df -h
sudo journalctl -xe
dmesg -T | tail
sudo systemctl status app

Common solution:

- Restart failed service
- Reboot instance if OS issue
- Stop/start if AWS host issue
- Extend disk if full
- Scale instance if CPU/memory high
- Replace unhealthy instance using ASG

---

## 10. Check Application logs

Application logs usually show real reason.

Check for:

- Port already in use
- DB connection failed
- Secrets missing
- Environment variable missing
- OutOfMemoryError
- Timeout
- 500 errors
- External API failure

Commands:

sudo journalctl -u app -f
docker logs <container-id>
kubectl logs <pod-name> -n <namespace>

Common solution:

- Restart app
- Rollback bad deployment
- Fix environment variable/secret
- Fix DB/cache connectivity
- Increase memory/CPU
- Fix application bug

---

## 11. Check Database/RDS

For RDS issues, check:

- RDS status is Available
- Endpoint and port are correct
- RDS SG allows App SG
- DatabaseConnections
- CPUUtilization
- FreeableMemory
- FreeStorageSpace
- ReadLatency/WriteLatency
- DiskQueueDepth
- RDS events

Connectivity test:

nc -vz <rds-endpoint> 3306
nc -vz <rds-endpoint> 5432

Common solution:

- Fix RDS SG
- Increase storage
- Enable storage autoscaling
- Scale RDS instance
- Kill blocking query carefully
- Add index/optimize slow query
- Fix DB connection pool
- Rollback bad DB migration

---

## 12. Check IAM permissions

For access denied issues, check:

- Exact error message
- Principal
- Action
- Resource
- IAM policy
- Resource policy
- Permission boundary
- SCP
- KMS key policy
- VPC endpoint policy

Command:

aws sts get-caller-identity

Common solution:

- Add missing IAM permission
- Fix resource ARN
- Remove/modify explicit deny
- Fix bucket/KMS/resource policy
- Check correct AWS account/role/profile
- Check IAM Identity Center permission set assignment

---

## 13. Check S3 permissions

For S3 access issue, check:

- Object exists
- Correct bucket/key
- IAM role has permission
- Bucket policy
- Block Public Access
- Object ownership
- KMS encryption
- VPC endpoint policy
- CloudFront OAC/OAI if used

Commands:

aws s3 ls s3://bucket-name/path/
aws s3api head-object --bucket bucket-name --key path/file.txt

Common solution:

- Add s3:GetObject
- Add s3:ListBucket
- Fix bucket policy
- Add kms:Decrypt
- Fix CloudFront OAC bucket policy
- Remove wrong explicit deny

---

## 14. Check Auto Scaling

For scaling issue, check:

- Desired capacity
- Minimum capacity
- Maximum capacity
- Scaling policy
- CloudWatch alarm
- ASG activity history
- Launch template
- AMI
- IAM instance profile
- Subnet IP availability
- EC2 quota
- Health check grace period

Common solution:

- Increase max capacity
- Fix scaling policy
- Fix launch template
- Use valid AMI
- Add more subnets/AZs
- Increase EC2 quota
- Fix user data
- Increase health check grace period

---

## 15. Check Recent Changes

Many production issues are caused by recent changes.

Check:

- Deployment
- Terraform/CloudFormation apply
- Security group change
- NACL change
- Route table change
- DNS change
- WAF rule change
- IAM policy change
- Secret/password rotation
- DB migration
- Certificate change

Use:

- CloudTrail
- AWS Config
- Deployment pipeline logs
- Git history
- Change ticket

Common solution:

- Rollback recent deployment
- Revert security group/routing/DNS change
- Restore previous launch template
- Restore previous secret
- Rollback Terraform/CloudFormation change

---

## 16. Check CloudWatch Metrics and Alarms

Check service-specific metrics.

EC2:
- CPUUtilization
- NetworkIn/Out
- StatusCheckFailed

ALB:
- TargetResponseTime
- RequestCount
- 5XX
- HealthyHostCount

RDS:
- CPUUtilization
- DatabaseConnections
- FreeStorageSpace
- ReadLatency/WriteLatency

EBS:
- VolumeQueueLength
- BurstBalance
- IOPSPercentage
- ThroughputPercentage

Common solution:

- Scale app
- Tune DB
- Increase EBS IOPS/throughput
- Fix unhealthy targets
- Tune alarms if noisy

---

## 17. Use AWS Logs and Audit Sources

Important sources:

- CloudTrail
- CloudWatch Logs
- ALB access logs
- VPC Flow Logs
- S3 access logs
- RDS logs
- GuardDuty findings
- AWS Config timeline

Use them to answer:

- Who changed what?
- When did it change?
- Which API failed?
- Which source IP?
- Which IAM principal?
- What was the error?

Common solution:

- Identify root cause
- Rollback bad change
- Disable compromised credentials
- Prepare RCA

---

## 18. Immediate Production Recovery Actions

During production issue, first restore service.

Possible actions:

- Rollback deployment
- Restart failed service
- Increase ASG desired capacity
- Increase ASG max capacity
- Re-add security group rule
- Fix listener/target group
- Increase RDS storage
- Scale RDS
- Restore previous DNS/WAF rule
- Disable bad IAM key
- Isolate compromised instance
- Failover to standby/DR if needed

Important:
Do not spend too much time on RCA before restoring service.

---

## 19. Common Preventive Actions

After fixing issue, prevent recurrence.

Preventive steps:

- Proper CloudWatch alarms
- Log monitoring
- Health checks
- Auto Scaling
- Multi-AZ RDS
- S3 versioning
- EBS/RDS storage alarms
- Least privilege IAM
- GuardDuty
- AWS Config
- CloudTrail enabled
- Backup validation
- Blue/green or canary deployments
- Rollback plan
- Runbooks
- Change approval
- Load testing

---

## 20. Generic Interview Answer Format

Use this format in interview:

1. First I will confirm the issue and impact.
2. Then I will check recent changes.
3. I will follow the request path from DNS to application/database.
4. I will check CloudWatch metrics, logs, and health checks.
5. I will isolate whether it is DNS, network, load balancer, compute, application, database, IAM, or storage issue.
6. For immediate recovery, I will rollback, restart, scale, or restore the last known good configuration.
7. After service recovery, I will do RCA and add preventive actions.

---

## Simple Universal Troubleshooting Flow

Issue reported
→ Confirm impact
→ Check recent changes
→ Check DNS
→ Check Load Balancer
→ Check Target Group
→ Check Security Groups
→ Check NACL/Route Table
→ Check Compute health
→ Check Application logs
→ Check Database/Cache
→ Check IAM/Permissions
→ Check CloudWatch/CloudTrail
→ Apply immediate fix
→ Verify service
→ Prepare RCA
→ Add prevention
