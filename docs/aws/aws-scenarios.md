# AWS Production Issue-Based Interview Questions and Answers
## For 3 Years Experienced DevOps Engineer

---

## 1. Application hosted on AWS is not accessible from the internet — how do you troubleshoot it?

I will troubleshoot the full traffic flow:

User → DNS → Load Balancer → Security Group → EC2/Application → Database/Dependency

Steps:
1. Check DNS resolution:
   nslookup example.com
   dig example.com

2. Check Route 53 record:
   - A/CNAME record should point to correct ALB, CloudFront, or public IP.
   - Public hosted zone should be used for internet-facing application.

3. Check Load Balancer:
   - ALB should be active.
   - Listener should exist on port 80/443.
   - Listener rule should forward traffic to correct target group.
   - Target group should have healthy targets.

4. Check security groups:
   - ALB SG should allow 80/443 from internet.
   - EC2/App SG should allow application port from ALB SG.

5. Check route table:
   - Public subnet should have 0.0.0.0/0 route to Internet Gateway.

6. Check application:
   sudo systemctl status app
   sudo ss -tulnp | grep 8080
   curl localhost:8080/health

7. Check application logs:
   sudo journalctl -u app -f
   docker logs <container-id>

Common causes:
- DNS points to wrong endpoint.
- ALB listener or rule is wrong.
- Target group is unhealthy.
- Security group is blocking traffic.
- Application is down.
- Health check path is wrong.
- Public subnet route table is missing Internet Gateway route.

Interview answer:
I will start from DNS and then check ALB listener, listener rules, target group health, security groups, route table, and application status. This helps me identify whether the issue is at DNS, load balancer, network, or application level.

---

## 2. EC2 instance is running but not reachable via SSH — what steps will you take?

SSH uses port 22. I will check whether the issue is with network, security group, key pair, username, or instance health.

Steps:
1. Verify correct public IP.

2. Check correct username:
   Ubuntu → ubuntu
   Amazon Linux → ec2-user
   CentOS → centos

3. Check key permission:
   chmod 400 key.pem
   ssh -i key.pem ubuntu@<public-ip>

4. Check security group:
   - Inbound SSH port 22 should be allowed from my public IP.

5. Check NACL:
   - Inbound port 22 should be allowed.
   - Outbound ephemeral ports should be allowed.

6. Check route table:
   - Public subnet should have 0.0.0.0/0 route to Internet Gateway.

7. Check if instance is in private subnet:
   - If private, use Bastion host, VPN, or SSM Session Manager.

8. Check EC2 status checks:
   - 2/2 checks should be passed.

9. If SSH service issue:
   sudo systemctl status ssh
   sudo systemctl restart ssh

Common causes:
- Wrong username.
- Wrong key pair.
- Security group blocking port 22.
- NACL blocking traffic.
- Instance is in private subnet.
- Route table missing Internet Gateway.
- SSH service stopped.
- OS firewall blocking SSH.

Interview answer:
I will verify public IP, username, and key pair first. Then I will check security group, NACL, route table, and subnet type. If the network is fine, I will check EC2 status checks, SSH service, and OS firewall using SSM Session Manager.

---

## 3. EC2 instance suddenly stopped responding — how do you investigate the issue?

I will check whether the issue is at AWS infrastructure level, OS level, resource level, or application level.

Steps:
1. Check EC2 state:
   - Running
   - Stopped
   - Rebooting

2. Check status checks:
   - System status check failed means AWS host issue.
   - Instance status check failed means OS/resource issue.

3. Check CloudWatch metrics:
   - CPUUtilization
   - NetworkIn/Out
   - StatusCheckFailed
   - Disk I/O

4. If instance is accessible:
   uptime
   top
   free -m
   df -h
   sudo journalctl -xe
   dmesg -T | tail

5. Check disk full:
   df -h
   sudo du -sh /var/log/*

6. Check memory issue:
   free -m
   dmesg | grep -i "out of memory"

7. Check application:
   sudo systemctl status app
   docker ps
   docker logs <container-id>

8. If SSH is not working:
   - Use SSM Session Manager.
   - Check EC2 system log.
   - Check instance screenshot.

9. Check recent changes in CloudTrail:
   - StopInstances
   - RebootInstances
   - ModifySecurityGroup
   - DetachVolume

Common causes:
- High CPU.
- Memory exhausted.
- Disk full.
- Kernel panic.
- Application crash.
- Bad deployment.
- Security group change.
- AWS underlying host issue.

Interview answer:
I will first check EC2 status checks and CloudWatch metrics. If system status check failed, I may stop/start the instance. If instance status check failed, I will investigate OS-level issues like CPU, memory, disk, kernel panic, and application crash using logs and SSM. I will also check CloudTrail for recent changes.

---

## 4. CPU utilization is very high on EC2 — how do you identify the root cause?

I will confirm the CPU spike in CloudWatch and then identify the process consuming CPU.

Steps:
1. Check CloudWatch CPUUtilization graph.

2. Login to EC2 and check:
   top
   htop
   ps aux --sort=-%cpu | head

3. Check load average:
   uptime
   nproc

4. If Java process is high:
   top -H -p <PID>
   jstack <PID> > thread-dump.txt

5. Check if traffic increased:
   - ALB RequestCount
   - NetworkIn/Out
   - Application access logs

6. Check recent deployment:
   sudo journalctl -u app --since "1 hour ago"

7. Check cron jobs:
   crontab -l
   ls -l /etc/cron*

8. Check memory and swap:
   free -m
   vmstat 1 5

9. Check suspicious process:
   ps aux --sort=-%cpu | head -20
   last

Common causes:
- High traffic.
- Bad deployment.
- Infinite loop in application.
- Batch job.
- Memory swapping.
- Malware or crypto miner.
- Instance size too small.

Interview answer:
I will check CloudWatch to confirm the spike and then use top, htop, ps, and uptime to identify the high CPU process. If it is a Java app, I will take thread dumps. I will also check traffic, recent deployments, cron jobs, memory pressure, and suspicious processes. For immediate mitigation, I may scale out, restart service, rollback, or upgrade instance type.

---

## 5. Application latency suddenly increased — how do you troubleshoot performance issues?

I will identify where latency is coming from:

Client → DNS/CDN → ALB → Application → Database → External dependency

Steps:
1. Check ALB metrics:
   - TargetResponseTime
   - RequestCount
   - HTTPCode_Target_5XX
   - HTTPCode_ELB_5XX
   - HealthyHostCount

2. Check EC2 metrics:
   - CPU
   - Memory
   - Disk
   - Network

3. On server:
   top
   free -m
   df -h
   iostat -xz 1

4. Check application logs:
   sudo journalctl -u app --since "1 hour ago"
   docker logs <container-id>

5. Check database metrics:
   - CPUUtilization
   - DatabaseConnections
   - FreeableMemory
   - ReadLatency
   - WriteLatency
   - DiskQueueDepth

6. Check connection pool errors:
   - HikariPool timeout
   - DB connection timeout
   - Thread pool exhausted

7. Check recent deployment or config change.

8. Check cache and external dependencies.

Common causes:
- High traffic.
- EC2 resource bottleneck.
- Slow database queries.
- DB connection pool exhausted.
- Cache failure.
- External API slow.
- Bad deployment.
- Only few healthy targets behind ALB.

Interview answer:
I will check ALB TargetResponseTime, request count, 5XX errors, and healthy hosts. Then I will check EC2 CPU, memory, disk, application logs, database metrics, connection pool, cache, and recent deployments. Based on the bottleneck, I will scale out, rollback, restart service, tune DB, or fix cache.

---

## 6. Traffic is not reaching the application behind an Application Load Balancer — what could be wrong?

I will troubleshoot this path:

User → DNS → ALB Listener → Listener Rule → Target Group → EC2/Application

Steps:
1. Check DNS:
   nslookup app.example.com
   dig app.example.com

2. Check ALB:
   - ALB should be active.
   - Scheme should be internet-facing for public application.
   - ALB should be in public subnets.

3. Check listener:
   - HTTP 80
   - HTTPS 443
   - Listener should forward to correct target group.

4. Check listener rules:
   - Correct host condition.
   - Correct path condition.
   - Correct priority.

5. Check target group:
   - Targets registered.
   - Targets healthy.
   - Correct port and protocol.

6. Check security groups:
   - ALB SG allows 80/443.
   - EC2 SG allows app port from ALB SG.

7. Check route table:
   - Public subnet should route to Internet Gateway.

8. Check application:
   sudo ss -tulnp | grep 8080
   curl localhost:8080/health

Common causes:
- DNS points to wrong ALB.
- ALB is internal.
- Listener missing.
- Listener rule forwards to wrong target group.
- Targets unhealthy.
- EC2 SG blocks ALB.
- Application not running.

Interview answer:
I will follow the request path from DNS to ALB listener, listener rules, target group, EC2 security group, and application port. Most issues are due to wrong listener rule, unhealthy target, blocked security group, or application not running.

---

## 7. Instances behind Load Balancer are marked unhealthy — how do you debug health check failures?

I will check whether the load balancer can reach the application on the correct port and health check path.

Steps:
1. Check target group health reason:
   - Request timed out
   - Connection refused
   - Response code mismatch
   - Health checks failed

2. Check health check config:
   - Protocol
   - Port
   - Path
   - Success code

3. Test locally on instance:
   curl -v http://localhost:8080/health

4. Test using private IP from another instance:
   curl -v http://<private-ip>:8080/health

5. Check app listening port:
   sudo ss -tulnp | grep 8080

6. Check EC2 security group:
   - App port should allow source as ALB SG.

7. Check ALB security group outbound.

8. Check NACL.

9. Check app logs:
   sudo journalctl -u app -f
   docker logs <container-id>

Common causes:
- Wrong health check path.
- Wrong health check port.
- Application not running.
- App returns 404/500.
- Security group blocks ALB.
- App listening only on 127.0.0.1.
- Startup time too long.

Interview answer:
I will check target group health reason, health check path, port, and success code. Then I will test the health endpoint locally and through private IP. I will also check whether the app is listening on the correct port, security groups, NACLs, and application logs.

---

## 8. Users cannot access application in private subnet — how do you troubleshoot connectivity?

Private subnet resources are not directly accessible from the internet. Correct design is:

Internet User → Public ALB → Private EC2/Application

Steps:
1. Confirm users are not trying to access private IP directly.

2. Check ALB:
   - ALB should be internet-facing.
   - ALB should be in public subnet.

3. Check public subnet route table:
   - 0.0.0.0/0 → Internet Gateway

4. Check DNS points to ALB.

5. Check ALB listener and target group.

6. Check target group health.

7. Check security groups:
   - ALB SG allows 80/443.
   - Private EC2 SG allows app port from ALB SG.

8. Check NACL.

9. Check application inside private instance:
   curl localhost:8080/health
   sudo ss -tulnp | grep 8080

Common causes:
- User trying to access private IP.
- No public ALB.
- ALB is internal.
- DNS points to wrong endpoint.
- Target group unhealthy.
- Private EC2 SG blocks ALB.
- Application not running.

Interview answer:
I will verify the architecture first because private subnet instances cannot be accessed directly from the internet. I will check DNS, internet-facing ALB, public subnet route, listener, target group health, security groups, NACLs, and application status.

---

## 9. Internet access is not working from private subnet instances — what checks will you perform?

Private subnet instances need NAT Gateway for outbound internet access.

Correct flow:

Private EC2 → NAT Gateway → Internet Gateway → Internet

Steps:
1. Check private subnet route table:
   - 0.0.0.0/0 → NAT Gateway

2. Check NAT Gateway:
   - Status should be Available.
   - NAT should be in public subnet.
   - NAT should have Elastic IP.

3. Check public subnet route table:
   - 0.0.0.0/0 → Internet Gateway

4. Check Internet Gateway attached to VPC.

5. Check EC2 security group outbound:
   - Allow HTTP/HTTPS or all outbound.

6. Check NACL:
   - Outbound 80/443 allowed.
   - Inbound ephemeral ports allowed.

7. Check DNS:
   ping 8.8.8.8
   nslookup google.com
   curl https://google.com

8. Check VPC DNS settings:
   - enableDnsSupport = true
   - enableDnsHostnames = true

Common causes:
- Missing NAT route.
- NAT Gateway not available.
- NAT Gateway in private subnet.
- Public subnet missing IGW route.
- Security group outbound blocked.
- NACL blocking ephemeral ports.
- DNS issue.

Interview answer:
I will check the private subnet route table and confirm that 0.0.0.0/0 points to NAT Gateway. Then I will verify NAT Gateway status, public subnet route to Internet Gateway, Elastic IP, security group outbound, NACL rules, and DNS resolution.

---

## 10. DNS is not resolving for your application — how do you troubleshoot Route 53?

I will check DNS record, hosted zone, delegation, TTL, and target endpoint.

Steps:
1. Test DNS:
   nslookup app.example.com
   dig app.example.com

2. Check Route 53 hosted zone:
   - Public hosted zone should exist.
   - Correct record should exist.

3. Check record type:
   - A Alias for ALB/CloudFront.
   - CNAME for DNS name.
   - A record for public IP.

4. Check record points to correct endpoint:
   - Correct ALB
   - Correct CloudFront
   - Correct EC2 public IP

5. Check domain delegation:
   dig NS example.com

6. Compare registrar name servers with Route 53 hosted zone NS records.

7. Check public vs private hosted zone.

8. Check TTL/cache.

9. Check Route 53 health checks if using failover.

10. If DNS resolves but app not opening, check ALB/CloudFront/application.

Common causes:
- Record missing.
- Wrong hosted zone.
- Private hosted zone used.
- Registrar NS not pointing to Route 53.
- Record points to old ALB/IP.
- TTL cache issue.
- Health check failover issue.

Interview answer:
I will first test DNS using nslookup and dig. Then I will check Route 53 public hosted zone, DNS record, endpoint, and name server delegation. I will also check public/private hosted zone, TTL, and health checks. If DNS resolves correctly, I will continue with ALB or application troubleshooting.

---

## 11. Files uploaded to S3 are not accessible — how do you debug permission issues?

I will check object existence, IAM permission, bucket policy, public access block, object ownership, KMS, and CloudFront if used.

Steps:
1. Check object exists:
   aws s3 ls s3://bucket-name/path/

2. Check exact object key because S3 is case-sensitive.

3. Check IAM permission:
   - s3:GetObject on arn:aws:s3:::bucket-name/*
   - s3:ListBucket on arn:aws:s3:::bucket-name

4. Check bucket policy for explicit deny.

5. Check S3 Block Public Access.

6. Check Object Ownership:
   - Bucket owner enforced is recommended.

7. If ACLs enabled, check object ACL.

8. If encrypted with KMS, check kms:Decrypt permission.

9. Check bucket policy conditions:
   - Source IP
   - VPC endpoint
   - HTTPS only
   - Specific IAM role

10. If using CloudFront:
   - Check OAC/OAI.
   - Check bucket policy allows CloudFront.

11. If using pre-signed URL:
   - Check expiry and signature.

Common causes:
- Wrong bucket/path.
- Missing s3:GetObject.
- Bucket policy deny.
- Block Public Access.
- Object ownership issue.
- KMS decrypt missing.
- VPC endpoint policy blocking.
- CloudFront OAC policy missing.
- Pre-signed URL expired.

Interview answer:
I will first check if the object exists and the path is correct. Then I will check IAM policy, bucket policy, explicit deny, Block Public Access, object ownership, KMS permission, VPC endpoint policy, and CloudFront OAC/OAI configuration if used.

---

## 12. S3 data transfer cost suddenly increased — how do you investigate?

I will identify which bucket, usage type, region, and access pattern caused the cost spike.

Steps:
1. Check Cost Explorer:
   - Service: Amazon S3
   - Group by usage type
   - Group by region
   - Group by linked account

2. Check usage types:
   - DataTransfer-Out-Bytes
   - Requests-Tier1
   - Requests-Tier2
   - Replication
   - Storage

3. Identify bucket using:
   - S3 Storage Lens
   - S3 server access logs
   - CloudTrail data events
   - CloudWatch request metrics

4. Check if bucket became public.

5. Check CloudFront:
   - Cache hit ratio
   - Origin fetches
   - Cache invalidations
   - TTL changes

6. Check cross-region transfer:
   - EC2 in one region accessing S3 in another region.
   - Cross-region replication.

7. Check application jobs:
   - Retry loop
   - Batch job
   - Backup job
   - Crawler/bot access

Common causes:
- Public bucket accessed by bots.
- High downloads from internet.
- CloudFront bypassed.
- CloudFront cache miss increased.
- Cross-region transfer.
- Replication enabled.
- Application retry loop.
- High GET/LIST requests.

Interview answer:
I will check Cost Explorer to identify usage type, region, and account. Then I will use S3 Storage Lens, access logs, CloudTrail data events, and CloudWatch metrics to find the bucket and access pattern. I will check public access, CloudFront cache hit ratio, cross-region transfer, replication, and application retry loops.

---

## 13. An EBS volume is full — how do you extend storage without downtime?

EBS volume size can be increased without stopping the instance. After AWS resize, partition and filesystem must be extended inside OS.

Steps:
1. Check disk usage:
   df -h
   lsblk

2. Find large files:
   sudo du -xh / | sort -h | tail -20

3. Take EBS snapshot for safety.

4. Modify volume:
   EC2 → Volumes → Modify volume

   Or CLI:
   aws ec2 modify-volume --volume-id vol-xxxx --size 50

5. Check new size:
   lsblk

6. Grow partition:
   sudo growpart /dev/nvme0n1 1

   For older device:
   sudo growpart /dev/xvda 1

7. Check filesystem type:
   df -Th

8. Extend filesystem:
   For ext4:
   sudo resize2fs /dev/nvme0n1p1

   For XFS:
   sudo xfs_growfs /

9. Verify:
   df -h

Common causes:
- Logs growing.
- Docker images.
- Temporary files.
- Database files.
- Backup files.
- No log rotation.

Interview answer:
I will first check disk usage using df -h and identify large files using du. Then I will take a snapshot, increase EBS volume size, grow the partition using growpart, and extend the filesystem using resize2fs for ext4 or xfs_growfs for XFS. Finally, I will verify using df -h and configure log rotation and disk alerts.

---

## 14. EBS volume performance is very slow — how do you troubleshoot I/O bottlenecks?

I will confirm whether disk I/O is the bottleneck and check EBS metrics, volume type, IOPS, throughput, and workload.

Steps:
1. Check I/O wait:
   top

   Look for high %wa.

2. Check disk stats:
   iostat -xz 1

3. Check EBS CloudWatch metrics:
   - VolumeReadOps
   - VolumeWriteOps
   - VolumeQueueLength
   - BurstBalance
   - IOPSPercentage
   - ThroughputPercentage

4. Check volume type:
   - gp2
   - gp3
   - io1/io2

5. For gp2:
   - Small volumes have low baseline IOPS.
   - BurstBalance may become low.

6. For gp3:
   - Increase IOPS and throughput separately if required.

7. Check EC2 instance EBS bandwidth limit.

8. Find process causing I/O:
   sudo iotop
   pidstat -d 1

9. Check disk full:
   df -h
   df -i

Common causes:
- gp2 burst credits exhausted.
- gp2 volume too small.
- gp3 IOPS/throughput too low.
- High VolumeQueueLength.
- Heavy logging.
- Backup job.
- Database workload.
- EC2 EBS bandwidth limit.
- Disk almost full.

Interview answer:
I will check top for I/O wait and iostat for disk latency/utilization. Then I will check EBS metrics like VolumeQueueLength, BurstBalance, IOPSPercentage, and ThroughputPercentage. I will verify volume type, provisioned IOPS, throughput, and EC2 EBS bandwidth. Based on root cause, I may move gp2 to gp3, increase IOPS/throughput, resize volume, optimize workload, or upgrade instance type.

---

## 15. Data stored in S3 is accidentally deleted — how do you recover it?

Recovery depends mainly on versioning, backup, replication, and lifecycle configuration.

Steps:
1. Identify bucket, prefix, object, and deletion time.

2. Check versioning:
   aws s3api get-bucket-versioning --bucket bucket-name

3. If versioning enabled, list object versions:
   aws s3api list-object-versions --bucket bucket-name --prefix path/file.txt

4. If delete marker exists, remove delete marker:
   aws s3api delete-object --bucket bucket-name --key path/file.txt --version-id <delete-marker-version-id>

5. Restore specific version:
   aws s3api copy-object --bucket bucket-name --copy-source bucket-name/path/file.txt?versionId=<old-version-id> --key path/file.txt

6. If versioning not enabled, check:
   - AWS Backup
   - Replication bucket
   - Application backup
   - Local copy
   - Data warehouse copy

7. Check lifecycle rules:
   - Maybe object moved to Glacier.

8. Check CloudTrail data events for DeleteObject.

Common causes:
- Manual deletion.
- Application bug.
- Wrong cleanup script.
- Lifecycle misconfiguration.
- CI/CD deleted wrong prefix.
- IAM permission too broad.

Interview answer:
I will first check whether S3 versioning is enabled. If yes, I will list object versions and remove the delete marker or restore a previous version. If versioning is not enabled, I will check backups, replication destination, AWS Backup, lifecycle archive, or application-level copies. I will also check CloudTrail data events to identify who deleted the object.

---

## 16. Amazon RDS instance is not accessible — how do you troubleshoot?

I will check endpoint, network, security group, NACL, DB status, credentials, and database logs.

Steps:
1. Check RDS status:
   - Available
   - Stopped
   - Modifying
   - Storage-full
   - Rebooting

2. Check correct endpoint and port:
   MySQL → 3306
   PostgreSQL → 5432

3. Test from application server:
   nc -vz <rds-endpoint> 3306
   nc -vz <rds-endpoint> 5432

4. Check RDS security group:
   - DB port should allow source as App SG.

5. Check app server outbound SG.

6. Check NACL:
   - DB port and ephemeral ports should be allowed.

7. Check subnet and route:
   - Same VPC or proper peering/TGW/VPN route.

8. Check public accessibility:
   - Private RDS cannot be accessed directly from laptop.

9. Check DB credentials:
   mysql -h <endpoint> -u admin -p
   psql -h <endpoint> -U postgres -d dbname

10. Check RDS logs and metrics.

Common causes:
- Wrong endpoint/port.
- RDS not available.
- RDS SG blocking App SG.
- NACL blocking traffic.
- Private RDS accessed from internet.
- Wrong credentials.
- Too many connections.
- Storage full.
- Recent security group/password change.

Interview answer:
I will first check RDS status, endpoint, and port. Then I will test connectivity from the application server using nc. If network fails, I will check RDS SG, app SG, NACL, route table, and subnet group. If connection works but login fails, I will check credentials, DB user permissions, Secrets Manager, RDS logs, and metrics.

---

## 17. Database performance degraded significantly — how do you analyze the issue?

I will check CPU, memory, connections, slow queries, locks, storage I/O, and recent changes.

Steps:
1. Check RDS CloudWatch metrics:
   - CPUUtilization
   - DatabaseConnections
   - FreeableMemory
   - FreeStorageSpace
   - ReadLatency
   - WriteLatency
   - DiskQueueDepth

2. Check Performance Insights:
   - DB load
   - Top SQL
   - Top waits
   - Top users
   - Top hosts

3. Check slow queries.

MySQL:
   SHOW FULL PROCESSLIST;

PostgreSQL:
   SELECT * FROM pg_stat_activity;

4. Check locks/deadlocks.

5. Check DB connections:
   - Too many connections can slow DB.

6. Check application connection pool:
   - HikariPool timeout
   - Connection is not available

7. Check storage I/O:
   - Read/write latency
   - Disk queue depth
   - IOPS

8. Check recent deployment or DB parameter change.

Common causes:
- Slow query.
- Missing index.
- Long-running transaction.
- Lock/deadlock.
- Too many connections.
- DB connection pool issue.
- High CPU.
- Low memory.
- Storage I/O bottleneck.
- Bad deployment.
- Cache failure.

Interview answer:
I will check RDS metrics, Performance Insights, slow query logs, active sessions, locks, and DB connections. I will also check application connection pool and recent deployments. Based on root cause, I will optimize queries/indexes, reduce connection pressure, scale RDS, add read replica, fix cache, or rollback.

---

## 18. RDS storage is full — how do you handle it in production?

First I will stabilize production by increasing storage, then investigate why storage filled.

Steps:
1. Check RDS status and FreeStorageSpace.

2. Check CloudWatch metrics:
   - FreeStorageSpace
   - WriteLatency
   - DiskQueueDepth
   - DatabaseConnections

3. Increase allocated storage:
   aws rds modify-db-instance --db-instance-identifier mydb --allocated-storage 200 --apply-immediately

4. Enable storage autoscaling.

5. Identify largest tables.

MySQL:
   SELECT table_schema, table_name,
   ROUND((data_length + index_length)/1024/1024,2) AS size_mb
   FROM information_schema.tables
   ORDER BY size_mb DESC
   LIMIT 10;

PostgreSQL:
   SELECT relname, pg_size_pretty(pg_total_relation_size(relid))
   FROM pg_catalog.pg_statio_user_tables
   ORDER BY pg_total_relation_size(relid) DESC
   LIMIT 10;

6. Check logs/WAL/binlogs/temp tables.

7. Archive or delete approved old data only.

8. Configure alarms:
   - FreeStorageSpace < 20%
   - FreeStorageSpace < 10%

Common causes:
- Normal data growth.
- Storage autoscaling disabled.
- Large audit/log table.
- WAL/binlog growth.
- Inactive replication slot.
- Application bug.
- Temporary tables.
- Files stored in DB.

Interview answer:
I will first check FreeStorageSpace and RDS status. For immediate recovery, I will increase allocated storage and apply immediately, or enable storage autoscaling. After stabilizing, I will identify large tables, indexes, logs, WAL/binlogs, and recent application changes. Then I will clean up or archive approved data and configure storage alarms.

---

## 19. Multi-AZ failover happened — how do you verify application recovery?

In Multi-AZ failover, RDS endpoint remains same but underlying primary changes. Application should connect using RDS endpoint, not IP.

Steps:
1. Check RDS status:
   - DB status should be Available.
   - Check RDS events for failover start/completion.

2. Test DB connectivity from app server:
   nc -vz <rds-endpoint> 3306
   nc -vz <rds-endpoint> 5432

3. Check application health:
   curl https://app.example.com/health

4. Check ALB metrics:
   - TargetResponseTime
   - Target 5XX
   - HealthyHostCount

5. Check application logs:
   - DB connection timeout
   - Connection reset
   - HikariPool errors

6. Check connection pool recovery.

7. Check DNS:
   nslookup <rds-endpoint>

8. Verify read/write operation.

9. Check RDS metrics:
   - Connections
   - CPU
   - Read/write latency

Common issues:
- App cached old DB IP.
- Connection pool stale connections.
- DNS TTL too high.
- Security group/NACL issue in new AZ.
- Long transactions failed.
- App hardcoded DB IP.

Interview answer:
I will check RDS events and confirm DB is Available. Then I will verify application connectivity using the same RDS endpoint. I will check app health, ALB metrics, application logs, connection pool recovery, DNS resolution, and read/write functionality. Finally, I will confirm business transactions are working.

---

## 20. Database backup failed — how do you investigate the failure?

I will first identify whether it is RDS automated backup, manual snapshot, AWS Backup job, or cross-region copy.

Steps:
1. Identify backup type:
   - RDS automated backup
   - Manual snapshot
   - AWS Backup
   - Cross-region snapshot copy

2. Check RDS events:
   - Backup failed
   - Storage full
   - KMS access denied
   - DB not available

3. Check DB status:
   - Available

4. Check backup retention:
   - Retention should be greater than 0.

5. Check backup window:
   - Avoid peak workload.

6. Check free storage space.

7. Check KMS key:
   - Key should be enabled.
   - IAM/KMS key policy should allow access.

8. If AWS Backup:
   - Check backup job details.
   - Check backup role permissions.
   - Check backup vault policy.

9. Check snapshot quota.

10. Check CloudTrail for recent changes.

Common causes:
- Backup retention disabled.
- DB not available.
- Storage full.
- KMS key disabled.
- IAM role missing permission.
- Backup window during heavy workload.
- Snapshot quota reached.
- Cross-region KMS issue.

Interview answer:
I will check the backup type and exact failure message. Then I will review RDS events, DB status, backup retention, backup window, storage, KMS key, IAM role, backup vault, and snapshot quota. After fixing the issue, I will trigger an on-demand backup and configure alerts for backup failures.

---

## 21. Users are unable to access AWS resources — how do you troubleshoot IAM permission issues?

I will identify principal, action, resource, and exact error.

IAM evaluation flow:

Principal → Identity Policy → Resource Policy → Permission Boundary → SCP → Session Policy

Steps:
1. Check exact error:
   - User is not authorized to perform s3:GetObject

2. Check caller identity:
   aws sts get-caller-identity

3. Check IAM policies attached to:
   - User
   - Group
   - Role

4. Check required action is allowed.

5. Check resource ARN is correct.

Example:
   s3:ListBucket → arn:aws:s3:::bucket
   s3:GetObject → arn:aws:s3:::bucket/*

6. Check explicit deny.

7. Check resource policies:
   - S3 bucket policy
   - KMS key policy
   - SQS/SNS/Lambda policy

8. Check permission boundary.

9. Check SCP if account is under AWS Organizations.

10. If IAM Identity Center:
   - Check account assignment.
   - Check permission set.
   - Check group membership.
   - Check permission set provisioning.

11. Use IAM Policy Simulator.

12. Check CloudTrail AccessDenied events.

Common causes:
- Required action missing.
- Wrong resource ARN.
- Explicit deny.
- Wrong AWS profile/account.
- SCP blocking.
- Permission boundary.
- Resource policy deny.
- KMS key policy missing.
- SSO assignment missing.

Interview answer:
I will check the exact AccessDenied error and identify principal, action, and resource. Then I will verify caller identity, IAM policies, resource policies, explicit deny, permission boundary, SCP, and KMS policy. If using IAM Identity Center, I will verify account assignment, permission set, and group membership. I will use Policy Simulator and CloudTrail for confirmation.

---

## 22. EC2 instance cannot access S3 — how do you debug IAM role problems?

I will check IAM role attached to EC2, role permissions, bucket policy, KMS, VPC endpoint policy, and AWS CLI credentials.

Steps:
1. Check exact error:
   - AccessDenied
   - Unable to locate credentials

2. Check IAM role attached:
   EC2 → Instance → Security → IAM Role

3. Check identity from EC2:
   aws sts get-caller-identity

4. If CLI shows IAM user instead of role:
   aws configure list
   env | grep AWS

5. Check role policy:
   - s3:ListBucket on bucket ARN
   - s3:GetObject on object ARN

6. Test access:
   aws s3 ls s3://bucket-name
   aws s3 cp s3://bucket-name/file.txt .

7. Check bucket policy for explicit deny.

8. For cross-account bucket, bucket policy must allow EC2 role.

9. If object encrypted with KMS:
   - Role needs kms:Decrypt.
   - KMS key policy should allow the role.

10. Check VPC endpoint policy if using S3 endpoint.

11. Check metadata service:
   curl http://169.254.169.254/latest/meta-data/iam/security-credentials/

Common causes:
- No IAM role attached.
- Wrong IAM role.
- Missing s3:GetObject/ListBucket.
- Wrong ARN.
- Static credentials overriding role.
- Bucket policy deny.
- KMS permission missing.
- VPC endpoint policy blocking.
- SCP or permission boundary.

Interview answer:
I will run aws sts get-caller-identity from EC2 to confirm the role. Then I will check if the correct IAM role is attached and whether it has required S3 permissions. I will also check bucket policy, explicit deny, KMS decrypt permission, VPC endpoint policy, and whether static credentials are overriding the instance role.

---

## 23. Unauthorized access detected — what immediate actions will you take?

First priority is containment, then evidence preservation, investigation, recovery, and prevention.

Flow:

Detect → Contain → Investigate → Eradicate → Recover → Prevent

Steps:
1. Do not delete evidence blindly.

2. Identify affected resource:
   - IAM user/access key
   - IAM role
   - EC2 instance
   - S3 bucket
   - RDS
   - Root account

3. If access key compromised:
   aws iam update-access-key --user-name user --access-key-id AKIAxxxx --status Inactive

4. If IAM role compromised:
   - Restrict trust policy.
   - Detach risky permissions.
   - Add temporary explicit deny if needed.

5. If EC2 compromised:
   - Remove from load balancer.
   - Attach isolation security group.
   - Block outbound.
   - Take EBS snapshots.
   - Preserve logs.

6. If S3 exposed:
   - Enable Block Public Access.
   - Remove public bucket policy/ACL.
   - Check object deletion/modification.

7. If root account involved:
   - Change password.
   - Enable/rotate MFA.
   - Delete root access keys if any.
   - Contact AWS Support if critical.

8. Check CloudTrail:
   - CreateUser
   - CreateAccessKey
   - AttachUserPolicy
   - AssumeRole
   - ConsoleLogin
   - AuthorizeSecurityGroupIngress
   - PutBucketPolicy
   - DeleteTrail
   - StopLogging

9. Check GuardDuty findings.

10. Check for persistence:
   - New users
   - New access keys
   - New roles
   - New admin policies
   - New security group rules
   - New EC2 instances

11. Rotate secrets.

12. Rebuild compromised resources from clean image.

Common immediate actions:
- Deactivate access keys.
- Disable suspicious users.
- Isolate EC2.
- Block public S3 access.
- Rollback SG changes.
- Preserve logs/snapshots.
- Rotate secrets.
- Check CloudTrail/GuardDuty.

Interview answer:
I will first contain the incident by disabling compromised keys, restricting IAM permissions, isolating EC2 instances, and blocking public exposure. I will preserve evidence using logs and snapshots. Then I will investigate CloudTrail and GuardDuty, check for persistence, rotate secrets, rebuild clean resources, and implement preventive controls like MFA, least privilege, GuardDuty, CloudTrail, and Access Analyzer.

---

## 24. Security group changes caused application downtime — how do you identify the issue?

I will identify which traffic path is broken.

Possible paths:
User → ALB
ALB → EC2/Application
Application → RDS
Application → External services

Steps:
1. Identify what is failing:
   - Website down
   - ALB targets unhealthy
   - App cannot connect to DB
   - SSH not working

2. Check ALB SG:
   - Inbound 80/443 from internet.

3. Check EC2/App SG:
   - App port from ALB SG.

4. Check RDS SG:
   - DB port from App SG.

5. Check outbound rules:
   - App to RDS.
   - App to internet/NAT.
   - App to S3/Redis/etc.

6. Check target group health.

7. Check CloudTrail events:
   - AuthorizeSecurityGroupIngress
   - RevokeSecurityGroupIngress
   - AuthorizeSecurityGroupEgress
   - RevokeSecurityGroupEgress
   - ModifySecurityGroupRules

8. Compare with previous config using:
   - Terraform Git history
   - AWS Config
   - CloudTrail
   - Change ticket

9. Use VPC Reachability Analyzer.

10. Test connectivity:
   curl localhost:8080/health
   nc -vz <rds-endpoint> 3306

Common causes:
- ALB 80/443 removed.
- App SG no longer allows ALB SG.
- RDS SG no longer allows App SG.
- Wrong source IP/SG added.
- Outbound restricted.
- Wrong SG attached.
- Terraform reverted manual change.

Interview answer:
I will first identify which path is broken: user to ALB, ALB to EC2, or app to DB. Then I will check ALB, EC2, and RDS security groups, target group health, and connectivity using curl/nc. I will use CloudTrail and AWS Config/Terraform history to find the change and restore the known good rule.

---

## 25. Auto Scaling Group is not launching new instances — how do you troubleshoot?

I will check capacity settings, scaling policies, activity history, launch template, subnet, quota, and capacity.

Steps:
1. Check ASG:
   - Desired capacity
   - Minimum capacity
   - Maximum capacity
   - Current instances

2. Check ASG activity history. This usually gives exact failure reason.

3. Check scaling policy and CloudWatch alarm:
   - Alarm should be in ALARM state.
   - Scaling policy should be attached.

4. Check cooldown and warmup.

5. Check launch template:
   - AMI exists.
   - Instance type valid.
   - Security group exists.
   - IAM instance profile exists.
   - Key pair exists.
   - User data valid.

6. Check subnet:
   - Available IP addresses.
   - Correct AZs selected.

7. Check EC2 service quota.

8. Check insufficient capacity in AZ.

9. If using Spot:
   - Check Spot capacity.
   - Use multiple instance types.

10. Check service-linked role:
   - AWSServiceRoleForAutoScaling

11. If instances launch and terminate:
   - Check health checks.
   - Check grace period.
   - Check user data logs.

Common causes:
- Desired capacity not increased.
- Max capacity too low.
- Alarm not triggered.
- Cooldown active.
- Deleted AMI.
- Invalid launch template.
- Subnet has no IPs.
- EC2 quota exceeded.
- Insufficient capacity.
- User data failure.
- Health check failure.

Interview answer:
I will first check desired, min, and max capacity. Then I will check ASG activity history because it usually shows the exact reason. I will verify scaling policy, CloudWatch alarm, cooldown, launch template, AMI, subnet IPs, EC2 quota, IAM profile, and capacity. If instances launch and terminate, I will check health checks and user data logs.

---

## 26. Instances are launching but not registering with Load Balancer — what could be wrong?

I will check ASG target group attachment, target group health, lifecycle state, security groups, and health checks.

Steps:
1. Check ASG load balancing section:
   - Correct target group should be attached.

2. Check Target Group → Targets:
   - Not listed
   - Initial
   - Unhealthy
   - Healthy

3. Check ASG activity history.

4. Check instance lifecycle state:
   - Should be InService.
   - Not stuck in Pending, Standby, or lifecycle hook.

5. Check target group type:
   - EC2 ASG usually uses instance target type.

6. Check target group VPC:
   - Target group and instances should be in same VPC.

7. Check health check:
   - Correct path.
   - Correct port.
   - Correct success code.

8. Check app startup time and ASG health check grace period.

9. Check SG:
   - EC2 SG should allow app port from ALB SG.

10. Check application:
   sudo ss -tulnp | grep 8080
   curl localhost:8080/health

11. Check user data:
   sudo cat /var/log/cloud-init-output.log

Common causes:
- ASG not attached to target group.
- Wrong target group.
- Different VPC.
- Wrong target type.
- Lifecycle hook stuck.
- Health check path/port wrong.
- App not running.
- App startup slow.
- SG blocks ALB.
- User data failed.

Interview answer:
I will check whether ASG has correct target group attached. Then I will check target group target status, ASG activity history, instance lifecycle state, health check config, security groups, app port, and user data logs. Most issues are due to missing target group attachment, failed health checks, or blocked ALB-to-instance traffic.

---

## 27. Application downtime occurred during deployment — how do you design zero-downtime deployments?

Zero downtime means old version should serve traffic until new version is healthy.

Flow:

Old version running → Deploy new version → Health check passes → Shift traffic → Remove old version

Steps:
1. Use rolling deployment:
   - Replace instances/pods gradually.
   - Keep minimum healthy capacity.

2. Use ALB health checks:
   - New instance should receive traffic only after healthy.

3. Configure ASG health check grace period.

4. Use launch template versioning and ASG Instance Refresh.

5. For Kubernetes:
   strategy:
     type: RollingUpdate
     rollingUpdate:
       maxUnavailable: 0
       maxSurge: 1

6. Configure readiness probe:
   - Only ready pods receive traffic.

7. Configure startup probe for slow apps.

8. Configure graceful shutdown:
   - terminationGracePeriodSeconds
   - preStop hook

9. Use PodDisruptionBudget.

10. For critical apps, use:
   - Blue/Green deployment
   - Canary deployment

11. Make database migrations backward compatible:
   - Add column first.
   - Deploy compatible app.
   - Backfill data.
   - Remove old column later.

12. Keep rollback plan ready.

Common causes of downtime:
- Old instances stopped before new ones ready.
- Wrong health checks.
- App startup slow.
- DB migration broke compatibility.
- Deployment replaced all instances at once.
- No rollback plan.

Interview answer:
I will design deployment so old version continues serving until the new version is healthy. I will use rolling deployment with health checks, minimum healthy capacity, readiness/startup probes, graceful shutdown, and proper rollback. For critical apps, I will use blue/green or canary deployment. I will also ensure database migrations are backward compatible.

---

## 28. Sudden spike in traffic caused application failure — how do you handle scaling issues?

First I will restore service, then find the bottleneck.

Traffic path:

ALB → EC2/Application → Database → Cache → External dependency

Steps:
1. Check ALB metrics:
   - RequestCount
   - TargetResponseTime
   - Target 5XX
   - ELB 5XX
   - HealthyHostCount

2. Check ASG:
   - Desired capacity
   - Max capacity
   - Scaling activity
   - New instances healthy or not

3. Immediate action:
   - Increase desired capacity.
   - Increase max capacity.
   - Add more instances.

4. Check EC2:
   top
   free -m
   df -h
   uptime

5. Check application logs:
   - OutOfMemory
   - Too many open files
   - DB timeout
   - Thread pool exhausted

6. Check RDS:
   - CPU
   - Connections
   - FreeableMemory
   - Read/write latency
   - DiskQueueDepth

7. Check cache:
   - Redis CPU/memory
   - Evictions
   - Cache hit ratio

8. Check connection limits:
   ulimit -n
   ss -s

9. If traffic is abusive:
   - Use AWS WAF rate-based rules.
   - Use throttling.

10. Improve scaling:
   - Target tracking
   - Step scaling
   - Scheduled scaling
   - Predictive scaling
   - Warm pool

Common causes:
- ASG max too low.
- Scaling policy too slow.
- New instances unhealthy.
- DB overloaded.
- DB connection pool exhausted.
- Cache failure.
- App thread pool exhausted.
- No rate limiting.
- Bot traffic.

Interview answer:
I will first check ALB metrics to confirm traffic spike and errors. Then I will check ASG capacity and manually scale if needed. I will check EC2 resources, application logs, RDS metrics, DB connections, cache health, and connection limits. If traffic is abusive, I will use WAF rate limiting. For prevention, I will tune autoscaling, caching, DB read replicas, and alerts.

---

## 29. CloudWatch alarms triggered frequently — how do you analyze and tune alerts?

I will check whether alerts are real issues or noisy false positives.

Steps:
1. Check alarm history:
   - State changes
   - Reason
   - Timestamp
   - Metric value

2. Check metric graph:
   - Short spike or continuous issue?
   - Normal baseline?
   - Deployment or batch job time?

3. Check if alert is actionable:
   - If no action is needed, it may be noisy.

4. Tune threshold:
   Example:
   CPU > 85% for 10 minutes instead of CPU > 60% for 1 minute.

5. Tune evaluation:
   - Period
   - Evaluation periods
   - Datapoints to alarm

Example:
   Trigger if 3 out of 5 datapoints breach threshold.

6. Use warning and critical alarms:
   - Warning to Slack/email.
   - Critical to on-call.

7. Check missing data treatment.

8. Use correct metric:
   - ALB TargetResponseTime
   - HTTPCode_Target_5XX
   - RDS FreeStorageSpace
   - RDS DatabaseConnections

9. Use metric math:
   - Alert on 5XX percentage instead of raw 5XX count.

10. Use anomaly detection for variable workloads.

11. Use composite alarms to reduce duplicate alerts.

12. Check notification routing.

Common causes:
- Threshold too low.
- Evaluation period too short.
- Single datapoint alert.
- Missing data treated as breaching.
- Wrong metric.
- Expected batch/backup job.
- Duplicate alarms.
- Non-prod alerts going to prod team.

Interview answer:
I will check alarm history and metric graph to see if it is a real issue or noise. Then I will tune threshold, period, evaluation periods, datapoints to alarm, and missing data behavior. I will use severity levels, metric math, anomaly detection, and composite alarms to reduce noise. Alerts should be actionable and routed to the correct team.

---

## 30. How do you troubleshoot a complete production outage in AWS?

For complete outage, first priority is service restoration, then root cause analysis.

Troubleshooting path:

User → DNS → CloudFront/WAF → ALB → EC2/ECS/EKS → Application → DB/Cache → Dependencies

Steps:
1. Confirm outage:
   curl -I https://app.example.com
   nslookup app.example.com

2. Check DNS/Route 53:
   - Record exists.
   - Points to correct ALB/CloudFront.
   - Hosted zone delegation is correct.

3. Check CloudFront/WAF if used:
   - Distribution status.
   - Origin health.
   - WAF rules.
   - Certificate.

4. Check ALB:
   - ALB active.
   - Listeners 80/443.
   - Listener rules.
   - Target group.

5. Check ALB metrics:
   - RequestCount
   - TargetResponseTime
   - ELB 5XX
   - Target 5XX
   - HealthyHostCount

6. Check target group health:
   - Are targets registered?
   - Are they healthy?
   - Failure reason?

7. Check compute:
   EC2:
   - ASG desired/current capacity.
   - EC2 status checks.
   - CPU/memory/disk.
   - Application service.

   EKS:
   kubectl get pods -n production
   kubectl get events -n production
   kubectl logs <pod> -n production

8. Check application logs:
   - DB errors
   - OOM
   - Config missing
   - Secret missing
   - External API timeout

9. Check database:
   - RDS status
   - CPU
   - Connections
   - FreeStorageSpace
   - Latency
   - Failover
   - Security group

10. Check cache and dependencies:
   - Redis
   - S3
   - Secrets Manager
   - External APIs

11. Check recent changes:
   - Deployment
   - SG/NACL/route table
   - DNS
   - WAF
   - IAM/secret
   - DB migration
   - Certificate
   - Terraform apply

12. Immediate recovery:
   - Rollback deployment.
   - Restore SG/DNS/WAF rule.
   - Scale ASG.
   - Restart service.
   - Increase RDS storage.
   - Failover if required.

13. After recovery, prepare RCA:
   - What happened?
   - Impact?
   - Root cause?
   - Fix?
   - Prevention?

Common causes:
- Bad deployment.
- All targets unhealthy.
- Security group/NACL change.
- DNS issue.
- WAF blocking traffic.
- RDS down/storage full.
- Secret rotation issue.
- Certificate expired.
- Auto Scaling failed.
- EKS/ECS rollout failure.

Interview answer:
I will troubleshoot from outside to inside: DNS, CloudFront/WAF, ALB, target group, compute, application, database, cache, and dependencies. In parallel, I will check recent changes using CloudTrail, AWS Config, and deployment logs. For immediate recovery, I will rollback, scale, restore configuration, or fix DB issue. After service recovery, I will prepare RCA and preventive actions.

---
