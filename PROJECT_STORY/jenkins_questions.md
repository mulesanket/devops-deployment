# Jenkins Interview Questions — AWS DevOps Engineer

## 1. Jenkins Basics

### 1. What is Jenkins?

Jenkins is an open-source automation server used mainly for CI/CD.

It helps automate:

- Code checkout
- Build
- Test
- Security scanning
- Artifact packaging
- Docker image creation
- Image publishing
- Deployment
- Notification

In DevOps, Jenkins acts as an automation orchestrator.

---

### 2. Why do we use Jenkins in DevOps?

We use Jenkins to automate repetitive build, test, and deployment tasks.

Instead of manually building and deploying applications, Jenkins creates a repeatable pipeline.

This improves:

- Speed
- Consistency
- Traceability
- Auditability
- Reliability
- Faster feedback to developers

---

### 3. What is CI/CD in Jenkins?

CI means Continuous Integration.

It validates code changes by running checkout, scan, build, test, package, image build, image scan, and publish stages.

CD means Continuous Delivery or Continuous Deployment.

It deploys the validated artifact or image to environments like dev, test, stage, and production.

Simple answer:

CI creates a trusted artifact.

CD deploys that artifact safely across environments.

---

### 4. What is the difference between Continuous Delivery and Continuous Deployment?

Continuous Delivery means the artifact is ready to deploy, but production deployment usually needs manual approval.

Continuous Deployment means every successful change can automatically go to production without manual approval.

In most enterprises, production usually follows Continuous Delivery with approval gates.

---

### 5. What is a Jenkins job?

A Jenkins job is an automation task configured in Jenkins.

Examples:

- Freestyle job
- Pipeline job
- Multibranch pipeline job
- Folder job

For modern CI/CD, pipeline jobs and multibranch pipelines are commonly used.

---

### 6. What is a Jenkins pipeline?

A Jenkins pipeline is a scripted or declarative workflow that defines CI/CD stages as code.

It is usually written in a Jenkinsfile and stored in Git.

Example stages:

- Checkout
- Build
- Test
- Scan
- Docker build
- Push image
- Deploy
- Notify

---

### 7. What is Jenkinsfile?

A Jenkinsfile is a file that contains the pipeline definition.

It is stored in the source code repository.

Benefits:

- Pipeline as code
- Version controlled
- Reviewable through pull requests
- Reusable
- Auditable

---

### 8. What are Jenkins plugins?

Plugins extend Jenkins functionality.

Common plugins:

- Git plugin
- Pipeline plugin
- Credentials Binding plugin
- Docker Pipeline plugin
- Kubernetes plugin
- SonarQube Scanner plugin
- Email Extension plugin
- Blue Ocean plugin
- AWS Steps plugin
- JUnit plugin

---

### 9. What is Jenkins controller?

Jenkins controller is the main Jenkins server.

It manages:

- UI
- Job configuration
- Scheduling
- Build queue
- Credentials
- Plugins
- Pipeline execution coordination
- Agent communication

Heavy build work should not run directly on the controller.

---

### 10. What is Jenkins agent?

Jenkins agent is a worker machine or pod where actual build steps run.

Agents can be:

- Static EC2 agents
- Docker agents
- Kubernetes pod agents
- Windows/Linux VMs

Best practice:

Controller should manage Jenkins.

Agents should execute builds.

---

## 2. Jenkins Architecture Questions

### 11. What is Jenkins controller-agent architecture?

Jenkins follows controller-agent architecture.

The controller manages jobs, users, credentials, and scheduling.

Agents execute the actual build, test, scan, and deployment commands.

This improves scalability because multiple builds can run on different agents.

---

### 12. Why should we not run builds on Jenkins controller?

Because the controller is responsible for managing Jenkins itself.

Running heavy builds on the controller can cause:

- High CPU usage
- Memory pressure
- Disk full issues
- Jenkins UI slowness
- Build queue delays
- Controller instability

Best practice is to run builds on agents.

---

### 13. What types of Jenkins agents have you used?

Possible answer:

We used Kubernetes-based ephemeral Jenkins agents.

Each build created a temporary pod with required containers like Maven, AWS CLI, Kaniko, and tools.

After the build completed, the pod was destroyed.

This helped avoid long-term workspace and disk cleanup issues.

---

### 14. What is a static Jenkins agent?

A static agent is a permanent machine connected to Jenkins.

Example:

- EC2 instance
- VM
- Bare-metal server

It remains available even after builds finish.

Disadvantage:

It requires maintenance, patching, disk cleanup, and tool installation.

---

### 15. What is an ephemeral Jenkins agent?

An ephemeral agent is created only for a build and destroyed after the build completes.

Example:

- Kubernetes pod agent

Benefits:

- Clean workspace for every build
- Better isolation
- Less disk cleanup
- Scalable
- No long-running build servers

---

### 16. What is Jenkins Kubernetes plugin?

Jenkins Kubernetes plugin allows Jenkins to dynamically create build agents as Kubernetes pods.

Each build can run in a dedicated pod.

The pod can have multiple containers such as:

- jnlp
- maven
- kaniko
- aws-cli
- trivy/tools

After the build finishes, the pod is deleted.

---

### 17. What is the benefit of Kubernetes-based Jenkins agents?

Benefits:

- Dynamic scaling
- Clean environment per build
- No static agent maintenance
- Better isolation
- Containerized build tools
- Easy to define build images
- Reduced disk cleanup issues

---

### 18. What is JNLP container in Jenkins Kubernetes agent?

The JNLP container connects the Jenkins agent pod to the Jenkins controller.

It handles communication between controller and agent.

The actual build commands can run in other containers like Maven, Kaniko, or AWS CLI.

---

### 19. Why use multiple containers in Jenkins agent pod?

Different stages need different tools.

Example:

- Maven container for Java build
- Kaniko container for Docker image build
- AWS CLI container for ECR/S3/EKS commands
- Tools container for Trivy/Gitleaks/jq

This avoids installing everything in one huge image.

---

### 20. What is the difference between Jenkins agent and Jenkins executor?

Agent is the machine or pod where builds run.

Executor is a slot inside an agent that can run one build.

If an agent has two executors, it can run two builds in parallel.

---

## 3. Jenkins Pipeline Questions

### 21. What are the two types of Jenkins pipeline syntax?

Two types:

- Declarative pipeline
- Scripted pipeline

Declarative pipeline is simpler, structured, and commonly used.

Scripted pipeline is more flexible but more complex.

---

### 22. What is declarative pipeline?

Declarative pipeline uses a structured syntax.

Example:

pipeline {
    agent any

    stages {
        stage('Build') {
            steps {
                sh 'mvn clean package'
            }
        }
    }
}

It is easier to read and maintain.

---

### 23. What is scripted pipeline?

Scripted pipeline uses Groovy-based scripting.

Example:

node {
    stage('Build') {
        sh 'mvn clean package'
    }
}

It gives more flexibility but can become harder to maintain.

---

### 24. What is agent in Jenkins pipeline?

agent defines where the pipeline or stage will run.

Examples:

agent any

agent none

agent {
    label 'maven-agent'
}

agent {
    kubernetes {
        yaml '''
        ...
        '''
    }
}

---

### 25. What is agent none?

agent none means no default agent is allocated for the entire pipeline.

Each stage must define its own agent.

This is useful when different stages need different agents or containers.

---

### 26. What are stages in Jenkins pipeline?

Stages divide the pipeline into logical parts.

Example:

- Checkout
- Build
- Test
- Scan
- Package
- Docker Build
- Push
- Deploy
- Notify

Stages make pipeline execution visible and easier to troubleshoot.

---

### 27. What are steps in Jenkins pipeline?

Steps are actual commands inside a stage.

Example:

sh 'mvn clean package'

sh 'kubectl rollout status deployment/auth-service'

archiveArtifacts artifacts: 'target/*.jar'

---

### 28. What is environment block in Jenkins?

environment block defines environment variables available to pipeline or stage.

Example:

environment {
    AWS_REGION = 'ap-south-1'
    SERVICE_NAME = 'auth-service'
}

---

### 29. What are parameters in Jenkins pipeline?

Parameters allow users to provide input before running a pipeline.

Examples:

- TARGET_ENV
- SERVICE_NAME
- IMAGE_TAG
- BRANCH_NAME
- CHANGE_TICKET

Used commonly in CD pipelines.

---

### 30. What is post block in Jenkins?

post block defines actions after pipeline or stage execution.

Example:

post {
    success {
        echo 'Build successful'
    }
    failure {
        echo 'Build failed'
    }
    always {
        cleanWs()
    }
}

Used for notifications, cleanup, report publishing, and rollback instructions.

---

### 31. What is when condition in Jenkins?

when condition controls whether a stage should run.

Example:

stage('Production Approval') {
    when {
        expression { params.TARGET_ENV == 'prod' }
    }
    steps {
        input message: 'Approve production deployment?'
    }
}

---

### 32. What is input step in Jenkins?

input step pauses the pipeline and waits for human approval.

Used for stage or production deployments.

Example:

input message: 'Approve production deployment?', ok: 'Deploy'

---

### 33. What is timeout in Jenkins?

timeout stops a pipeline or stage if it runs longer than expected.

Example:

options {
    timeout(time: 30, unit: 'MINUTES')
}

This prevents stuck builds.

---

### 34. What is retry in Jenkins?

retry reruns a block if it fails.

Example:

retry(3) {
    sh 'curl -f https://service/health'
}

Used for temporary network or service issues.

---

### 35. What is timestamps option?

timestamps adds time to Jenkins console logs.

Useful for debugging performance and delays.

Example:

options {
    timestamps()
}

---

### 36. What is disableConcurrentBuilds?

disableConcurrentBuilds prevents multiple builds of the same job from running at the same time.

Useful for deployment jobs to avoid overlapping deployments.

Example:

options {
    disableConcurrentBuilds()
}

---

### 37. What is buildDiscarder?

buildDiscarder controls how many old builds Jenkins should keep.

Example:

options {
    buildDiscarder(logRotator(numToKeepStr: '10'))
}

This helps manage Jenkins disk usage.

---

### 38. What is archiveArtifacts?

archiveArtifacts stores generated files in Jenkins build record.

Examples:

- JAR files
- scan reports
- logs
- manifests

Example:

archiveArtifacts artifacts: 'target/*.jar', fingerprint: true

---

### 39. What is fingerprinting in Jenkins?

Fingerprinting tracks artifact usage across builds and jobs.

It helps identify which build produced or used a specific artifact.

---

### 40. What is stash and unstash in Jenkins?

stash temporarily saves files during a pipeline.

unstash restores them in another stage or agent.

Useful when stages run on different agents.

Example:

stash name: 'jar', includes: 'target/*.jar'

unstash 'jar'

---

## 4. Jenkins CI Pipeline Questions

### 41. Explain your Jenkins CI pipeline stages.

Answer:

Our CI pipeline started with source checkout, then environment setup, secret scanning, dependency scanning, build, unit testing, SonarQube quality gate, integration testing, package artifact, container image build, image scan, publish to ECR/artifact store, and notify.

The goal was to fail fast, validate security and quality, create a trusted artifact, build a Git SHA tagged image, scan it, publish it, and notify the team.

---

### 42. Why do you keep secret scanning early in CI?

Secret scanning is fast and security-critical.

If secrets are found, we should fail immediately before build, test, packaging, or image creation.

There is no point building code that contains exposed credentials.

---

### 43. Which tool did you use for secret scanning?

Common answer:

We used Gitleaks.

It detects hardcoded secrets like AWS keys, API tokens, private keys, certificates, passwords, and database credentials.

---

### 44. What do you do if secret scanning fails?

Steps:

1. Check Gitleaks report.
2. Identify file and line number.
3. Confirm real secret or false positive.
4. Remove secret from code.
5. Rotate exposed credential.
6. Move secret to Jenkins credentials, AWS Secrets Manager, or Parameter Store.
7. Re-run pipeline.

Important:

Removing the secret is not enough. It must be rotated.

---

### 45. What is SCA?

SCA means Software Composition Analysis.

It scans third-party dependencies for known vulnerabilities.

Examples:

- Maven dependencies
- npm packages
- Python packages
- Go modules

Tools:

- OWASP Dependency-Check
- Snyk
- Trivy fs
- Mend

---

### 46. What is the difference between SAST and SCA?

SAST checks your own source code.

SCA checks third-party dependencies.

Example:

SAST may find insecure code pattern.

SCA may find vulnerable Log4j version.

---

### 47. Why do we run dependency scanning before build/package?

Because vulnerable dependencies should be caught early.

If a high or critical CVE exists, the pipeline should fail before the artifact is packaged and deployed.

---

### 48. What do you do if OWASP Dependency-Check fails?

Steps:

1. Open dependency report.
2. Check dependency name and version.
3. Check CVE ID and severity.
4. Check if it is direct or transitive dependency.
5. Run mvn dependency:tree.
6. Upgrade dependency or parent BOM.
7. Suppress only if approved false positive.

---

### 49. What is SonarQube used for?

SonarQube is used for code quality and static analysis.

It checks:

- Bugs
- Vulnerabilities
- Security hotspots
- Code smells
- Duplicate code
- Complexity
- Coverage
- Quality Gate

---

### 50. What is SonarQube Quality Gate?

Quality Gate is a set of rules that code must pass.

Example:

- No blocker issues
- Coverage above threshold
- No new critical vulnerabilities
- Duplication below threshold

If Quality Gate fails, Jenkins stops the pipeline.

---

### 51. Why run SonarQube after unit tests?

Because SonarQube can use:

- Compiled code
- JUnit reports
- JaCoCo coverage reports

This gives better quality and coverage analysis.

---

### 52. What do you do if SonarQube shows 0% coverage?

Check:

- JaCoCo report generated or not
- Correct report path
- Maven module path
- Sonar project key
- Test reports published
- Maven command executed from correct directory

---

### 53. What is the difference between unit test and integration test?

Unit test checks small pieces of logic in isolation.

Integration test checks multiple components working together.

Unit tests use mocks.

Integration tests may use database, test containers, mock services, or test environment.

---

### 54. How are test reports published in Jenkins?

Using JUnit publisher.

Example:

junit 'target/surefire-reports/*.xml'

For integration tests:

junit 'target/failsafe-reports/*.xml'

---

### 55. What is build once, deploy many?

It means we build the artifact or image once in CI and promote the same artifact across environments.

We do not rebuild separately for dev, test, stage, and prod.

This improves consistency and traceability.

---

### 56. Why tag Docker images with Git SHA?

Git SHA gives immutable traceability.

It maps image to:

- Git commit
- Jenkins build
- Source code
- Scan reports
- Deployment version

Avoid relying only on latest because latest can change.

---

### 57. What is the difference between image tag and image digest?

Image tag is a human-friendly label like a1b2c3d.

Image digest is a unique SHA256 content address of the image.

Digest is more immutable and precise.

---

### 58. Why scan container image after build?

Because the final image may contain vulnerabilities from:

- Base image
- OS packages
- Java runtime
- Application dependencies
- Dockerfile-installed packages

SCA alone is not enough.

---

### 59. Which image scanning tool did you use?

Common answer:

We used Trivy to scan container images.

It detects vulnerabilities in OS packages, language dependencies, and image layers.

---

### 60. What is publish stage in CI?

Publish stage pushes the trusted image to ECR and stores artifacts/reports.

Outputs:

- Docker image
- JAR artifact
- Test reports
- Scan reports
- SonarQube link
- Build manifest

---

## 5. Jenkins CD Pipeline Questions

### 61. Explain your Jenkins CD pipeline.

Answer:

After CI pushed the Git SHA tagged image to ECR, the CD pipeline selected the service, target environment, and image tag.

Since dev, test, stage, and prod were separate AWS accounts, Jenkins connected to the target AWS account using environment-specific IAM role and updated kubeconfig for that account’s EKS cluster.

Then Jenkins verified the image in ECR, performed Kubernetes pre-checks, used manual approval for stage/prod, deployed using kubectl set image, validated rollout, ran smoke tests, and notified the team.

Rollback was done using kubectl rollout undo or previous stable image tag.

---

### 62. How did you deploy without Helm?

We used kubectl.

For normal application releases, base Kubernetes resources were already present.

Jenkins only updated the image in the existing Deployment using kubectl set image.

Example:

kubectl set image deployment/auth-service \
  auth-service=<image-uri> \
  -n shopease-dev

If manifest changes were needed, we used kubectl apply after review.

---

### 63. What is kubectl set image?

kubectl set image updates the container image of an existing Kubernetes Deployment.

It triggers a rolling update.

Example:

kubectl set image deployment/auth-service auth-service=repo/auth-service:a1b2c3d -n dev

---

### 64. When do you use kubectl apply instead of kubectl set image?

Use kubectl set image when only image tag changes.

Use kubectl apply when Kubernetes manifests change.

Examples:

- resource limits
- probes
- ConfigMap
- Secret reference
- HPA
- PDB
- Ingress
- ServiceAccount

---

### 65. How did you handle separate AWS accounts for each environment?

Jenkins mapped each environment to:

- AWS account ID
- IAM deployment role
- EKS cluster name
- namespace
- base URL

Then Jenkins assumed the correct role and updated kubeconfig for that EKS cluster.

---

### 66. How did Jenkins connect to EKS?

Jenkins used AWS authentication first.

Then it ran:

aws eks update-kubeconfig --region ap-south-1 --name <cluster-name>

After that, kubectl commands were executed against the target EKS cluster.

---

### 67. Why run aws sts get-caller-identity in Jenkins?

To validate AWS identity.

It confirms:

- AWS account ID
- IAM role/user
- assumed role session

It helps catch wrong AWS account or wrong role before deployment.

---

### 68. What pre-checks did you perform before deployment?

Checks:

- namespace exists
- deployment exists
- service exists
- ConfigMap exists
- Secret exists
- RBAC permission
- cluster reachable
- image exists in ECR

Commands:

kubectl get ns

kubectl get deploy

kubectl get svc

kubectl auth can-i patch deployment

aws ecr describe-images

---

### 69. How are manual gates implemented in Jenkins?

Using parameterized pipeline and input step.

Example:

input message: 'Approve production deployment?', ok: 'Deploy'

For prod, approval can be restricted using submitter.

---

### 70. Why use manual approval for production?

Because production requires governance.

Manual approval ensures:

- release reviewed
- QA completed
- change ticket approved
- deployment window confirmed
- authorized person approves release

---

### 71. How did you validate deployment?

Using:

kubectl rollout status

kubectl get pods

kubectl get svc

kubectl get endpoints

curl health endpoint

Monitoring logs and metrics

---

### 72. What is smoke testing in CD?

Smoke testing is a lightweight check after deployment.

It validates that the application is reachable and critical endpoints work.

Example:

curl -f https://dev.example.com/api/auth/health

---

### 73. Difference between rollout validation and smoke test?

Rollout validation checks Kubernetes deployment state.

Smoke test checks application response from user/service point of view.

Rollout can pass but smoke test can fail if ingress, service, or app route is broken.

---

### 74. How did you promote the image across environments?

Same Git SHA image was promoted.

Flow:

dev -> test -> stage -> prod

We did not rebuild image.

Only environment configuration changed.

---

### 75. What is rollback strategy in Jenkins CD?

Rollback means reverting Kubernetes Deployment to previous stable image.

Methods:

kubectl rollout undo

kubectl set image with previous Git SHA

helm rollback if Helm is used

In our case, we used kubectl rollout undo or redeployed previous stable image tag.

---

## 6. Jenkins Secrets Management Questions

### 76. How do you manage secrets in Jenkins?

We use Jenkins Credentials Manager.

Secrets are injected using withCredentials.

We never hardcode secrets in Jenkinsfile, Git repo, Dockerfile, or Kubernetes YAML.

For AWS, we prefer IAM roles, EC2 instance profile, or IRSA instead of static keys.

Application runtime secrets are stored in AWS Secrets Manager or Parameter Store.

---

### 77. What is Jenkins Credentials Manager?

It is Jenkins’ secure storage for credentials.

Credential types:

- username/password
- secret text
- SSH key
- certificate
- file credential

---

### 78. How do you use secrets in Jenkinsfile?

Using withCredentials.

Example:

withCredentials([string(credentialsId: 'sonar-token', variable: 'SONAR_TOKEN')]) {
    sh '''
        set +x
        sonar-scanner -Dsonar.login=$SONAR_TOKEN
    '''
}

---

### 79. Why use set +x?

set +x disables command echoing in shell.

It helps prevent secrets from appearing in Jenkins logs.

---

### 80. Why avoid storing AWS keys in Jenkins?

Static AWS keys are long-lived and risky.

Better options:

- EC2 instance profile
- IAM role assumption
- IRSA for EKS-based agents

These provide temporary credentials and better security.

---

### 81. What is credentials binding plugin?

Credentials Binding plugin allows Jenkins credentials to be injected into build environment variables temporarily.

It also masks secrets in console output.

---

### 82. What are folder-level credentials?

Folder-level credentials are scoped to a Jenkins folder.

They restrict which jobs can access certain credentials.

Useful for separating teams or environments.

---

### 83. What if secret is printed in Jenkins logs?

Actions:

1. Stop using echo or command trace.
2. Use set +x.
3. Rotate exposed secret.
4. Delete or restrict build logs if required.
5. Move secret to credentials manager.
6. Review Jenkinsfile.

---

### 84. How do you manage application secrets?

Application secrets should not be stored in Jenkins.

Use:

- AWS Secrets Manager
- AWS SSM Parameter Store
- External Secrets Operator
- Kubernetes Secrets

Jenkins should only manage CI/CD execution secrets.

---

## 7. Jenkins + Docker + ECR Questions

### 85. How does Jenkins build Docker images?

Jenkins can use:

- Docker daemon
- Kaniko
- BuildKit
- Buildah
- Jib for Java

In Kubernetes agents, Kaniko is preferred because it does not require privileged Docker-in-Docker.

---

### 86. Why use Kaniko?

Kaniko builds container images inside Kubernetes without Docker daemon.

Benefits:

- No privileged Docker socket
- Better for Kubernetes agents
- Safer than Docker-in-Docker
- Works well in CI pipelines

---

### 87. How does Jenkins push image to ECR?

Steps:

1. Authenticate to AWS.
2. Login to ECR.
3. Build image.
4. Tag image.
5. Push image to ECR.

Example:

aws ecr get-login-password --region ap-south-1 | docker login --username AWS --password-stdin <ecr-url>

docker push <image-uri>

For Kaniko, credentials are passed through AWS IAM role or ECR credential helper.

---

### 88. What ECR permissions are needed?

Common permissions:

- ecr:GetAuthorizationToken
- ecr:BatchCheckLayerAvailability
- ecr:InitiateLayerUpload
- ecr:UploadLayerPart
- ecr:CompleteLayerUpload
- ecr:PutImage
- ecr:DescribeImages
- ecr:BatchGetImage

---

### 89. What causes ECR push failure?

Possible causes:

- wrong AWS account
- wrong region
- ECR repo missing
- IAM permission missing
- authentication failed
- image tag invalid
- network issue
- repository policy issue

---

### 90. What causes ImagePullBackOff after deployment?

Possible causes:

- image tag does not exist
- wrong image URI
- ECR permissions issue
- wrong region/account
- private registry auth issue
- image deleted by lifecycle policy
- node cannot access ECR

---

## 8. Jenkins + Kubernetes Questions

### 91. How does Jenkins deploy to Kubernetes?

Jenkins can deploy using:

- kubectl
- Helm
- Kustomize
- Argo CD
- Flux
- Spinnaker

In our case, we used kubectl.

---

### 92. How does Jenkins authenticate to Kubernetes?

For EKS, Jenkins first authenticates to AWS using IAM.

Then it updates kubeconfig using:

aws eks update-kubeconfig

Kubernetes access depends on EKS access entries, aws-auth configmap, or IAM mapping.

---

### 93. What Kubernetes permissions does Jenkins need?

For deployment, Jenkins may need:

- get/list deployments
- patch/update deployments
- get pods
- get services
- get endpoints
- get events
- get configmaps/secrets if pre-checks are needed

Prefer least privilege.

---

### 94. How to check Jenkins Kubernetes permission?

Use:

kubectl auth can-i patch deployment -n <namespace>

Example:

kubectl auth can-i patch deployment -n shopease-dev

---

### 95. What if kubectl command fails in Jenkins?

Check:

- kubeconfig
- current context
- AWS role
- EKS access mapping
- Kubernetes RBAC
- namespace
- cluster endpoint access
- network connectivity
- kubectl version compatibility

---

### 96. What is kubectl rollout status?

It waits for a deployment rollout to complete.

Example:

kubectl rollout status deployment/auth-service -n dev --timeout=5m

If pods do not become ready, it fails.

---

### 97. What is kubectl rollout undo?

It rolls back a deployment to previous ReplicaSet revision.

Example:

kubectl rollout undo deployment/auth-service -n prod

---

### 98. What is kubectl rollout history?

It shows deployment rollout revisions.

Example:

kubectl rollout history deployment/auth-service -n prod

---

### 99. What is revisionHistoryLimit?

revisionHistoryLimit controls how many old ReplicaSets Kubernetes keeps for rollback.

Example:

revisionHistoryLimit: 5

If it is too low, rollback history may not be available.

---

### 100. What Kubernetes issues can Jenkins CD catch?

Jenkins CD can catch:

- ImagePullBackOff
- CrashLoopBackOff
- readiness probe failure
- missing secret
- missing ConfigMap
- wrong image tag
- insufficient resources
- RBAC failure
- namespace not found
- service endpoint issue

---

## 9. Jenkins Shared Library Questions

### 101. What is Jenkins shared library?

A Jenkins shared library is reusable Groovy code used across multiple pipelines.

It avoids duplication.

Example:

- common logging functions
- common build functions
- common Docker build logic
- common notification logic
- common Kubernetes deploy logic

---

### 102. Why use Jenkins shared library?

Benefits:

- Reusable pipeline logic
- Standardized stages
- Less duplication
- Easier maintenance
- Consistent CI/CD practices across services

---

### 103. How do you use shared library in Jenkinsfile?

Example:

@Library('my-shared-library') _

Then call shared functions:

logger.stageHeader('Build')

---

### 104. What is vars directory in shared library?

vars directory contains global pipeline functions.

Example:

vars/buildMaven.groovy

Can be called as:

buildMaven()

---

### 105. What is src directory in shared library?

src directory contains Groovy classes and reusable library code.

Used for more structured shared logic.

---

### 106. What is resources directory in shared library?

resources directory stores static files/templates used by library.

Examples:

- YAML templates
- email templates
- scripts

---

## 10. Jenkins Security Questions

### 107. How do you secure Jenkins?

Best practices:

- Use RBAC
- Use folder-level permissions
- Use credentials manager
- Do not hardcode secrets
- Use agents for builds
- Keep plugins updated
- Restrict script approvals
- Enable CSRF protection
- Use HTTPS
- Backup Jenkins
- Use least privilege service accounts
- Avoid running builds on controller

---

### 108. What is Jenkins RBAC?

RBAC controls who can access Jenkins and what actions they can perform.

Examples:

- read job
- build job
- configure job
- approve deployment
- manage credentials
- admin access

---

### 109. How do you restrict production deployment approval?

Using Jenkins input step with submitter.

Example:

input message: 'Approve prod?', submitter: 'release-managers,devops-leads'

Also use Jenkins RBAC to restrict job permissions.

---

### 110. How do you prevent secrets from leaking in Jenkins?

Practices:

- Use credentials manager
- Use withCredentials
- Use set +x
- Avoid echoing secrets
- Mask passwords
- Avoid secrets in command arguments
- Restrict credential scope
- Rotate leaked secrets

---

### 111. What is script approval in Jenkins?

Jenkins may require approval for certain Groovy scripts or methods.

This protects Jenkins from unsafe script execution.

Admins can approve scripts under In-process Script Approval.

---

### 112. Why should Jenkins plugins be updated carefully?

Outdated plugins may have security vulnerabilities.

But plugin updates can also break compatibility.

Best practice:

- test updates in non-prod Jenkins
- backup Jenkins
- update during maintenance window
- review plugin changelog

---

### 113. How do you backup Jenkins?

Backup:

- JENKINS_HOME
- job configs
- credentials
- plugin list
- user configs
- shared library config
- secrets files
- pipeline configs

Tools:

- thinBackup plugin
- filesystem snapshot
- EBS snapshot
- S3 backup
- configuration as code

---

## 11. Jenkins Troubleshooting Questions

### 114. Jenkins build is stuck in queue. What will you check?

Check:

- agent availability
- label mismatch
- executor availability
- offline agents
- pending Kubernetes pods
- resource quota
- node capacity
- Jenkins controller load
- throttling/concurrent build settings

---

### 115. Jenkins agent is offline. What will you check?

Check:

- network connectivity
- agent service status
- SSH/JNLP connection
- Java version
- disk space
- credentials
- firewall/security group
- Jenkins URL
- controller logs

For Kubernetes agents:

- pod status
- events
- image pull errors
- service account/RBAC
- resource requests

---

### 116. Jenkins build failed at checkout. What will you check?

Check:

- repository URL
- branch name
- credentials/token
- webhook trigger
- Git plugin
- network access
- refspec
- GitHub/GitLab permissions
- rate limit

---

### 117. Jenkins build failed at Maven build. What will you check?

Check:

- console logs
- pom.xml
- Java version
- Maven version
- dependency resolution
- Nexus/Artifactory access
- settings.xml
- test failures
- module path

---

### 118. Jenkins pipeline syntax error. What will you check?

Check:

- braces
- stage/steps/script block placement
- declarative syntax
- Jenkinsfile validator
- missing commas/quotes
- shared library function syntax
- indentation and nesting

---

### 119. Jenkins workspace is full. What will you do?

Actions:

- delete old workspaces
- configure buildDiscarder
- cleanWs after build
- archive required artifacts before cleanup
- remove old Docker images
- prune dangling layers
- use ephemeral Kubernetes agents
- increase disk if needed

---

### 120. Jenkins controller disk is full. What will you check?

Check:

- old builds
- archived artifacts
- workspaces
- logs
- plugin caches
- Docker images if controller builds images
- JENKINS_HOME size
- backup files

Use:

du -sh $JENKINS_HOME/*

---

### 121. Jenkins email notification not working. What will you check?

Check:

- Email Extension plugin
- SMTP server
- SMTP port
- credentials
- sender address
- recipient list
- post block
- network/firewall
- TLS settings
- Jenkins system config

---

### 122. Jenkins job is running old code. What will you check?

Check:

- branch selected
- commit SHA in logs
- workspace cleanup
- multibranch indexing
- webhook
- Git refspec
- shallow clone issue
- Jenkinsfile loaded from correct branch

---

### 123. Jenkins build is slow. What will you check?

Check:

- dependency download time
- Maven cache
- npm cache
- Docker image pull time
- image build context
- scan time
- agent provisioning delay
- test execution time
- resource limits
- parallelization opportunities

---

### 124. Jenkins pipeline fails randomly. What will you check?

Check:

- flaky tests
- network issues
- dependency repository availability
- agent resource limits
- timeout values
- API rate limits
- external service dependencies
- race conditions
- workspace conflicts

---

### 125. Jenkins Kubernetes agent pod stays Pending. What will you check?

Check:

- node capacity
- resource requests
- taints/tolerations
- node selectors
- image pull secrets
- namespace quota
- PVC binding
- pod events
- scheduler events

---

### 126. Jenkins Kubernetes agent pod fails with ImagePullBackOff. What will you check?

Check:

- agent image name
- image tag
- registry access
- imagePullSecret
- ECR auth
- network to registry
- repository exists
- permissions

---

### 127. Jenkins Kubernetes agent pod starts but build does not run. What will you check?

Check:

- JNLP container logs
- Jenkins URL
- agent secret
- service account
- network connectivity
- WebSocket/JNLP port
- controller accessibility
- Kubernetes plugin config

---

### 128. Jenkins job cannot access AWS. What will you check?

Check:

- aws sts get-caller-identity
- IAM role
- instance profile
- IRSA annotation
- trust policy
- IAM permissions
- AWS region
- credentials binding
- environment variables

---

### 129. Jenkins cannot push to ECR. What will you check?

Check:

- AWS identity
- ECR repo exists
- region
- repository name
- login command
- IAM permissions
- repository policy
- image tag
- network

---

### 130. Jenkins deployment failed in Kubernetes. What will you check?

Check:

- kubectl context
- namespace
- deployment name
- container name
- image URI
- ECR image exists
- rollout status
- pod logs
- pod describe
- events
- ConfigMap/Secret
- readiness/liveness probes
- RBAC

---

## 12. Jenkins Scenario-Based Questions

### 131. Scenario: Pipeline failed because Gitleaks found a secret. What will you do?

Answer:

I will open the Gitleaks report, identify the file and line number, confirm whether it is a real secret, and fail the pipeline.

If it is real, I will ask the developer to remove it from code and rotate the exposed credential.

Then the secret should be moved to Jenkins credentials, AWS Secrets Manager, or Parameter Store.

---

### 132. Scenario: Jenkins pipeline failed at SonarQube Quality Gate. What will you do?

Answer:

I will open the SonarQube dashboard, check which condition failed, such as low coverage, critical bug, vulnerability, duplication, or code smell.

Then I will share the rule, file path, and line number with developers.

The pipeline should not proceed to artifact/image build until the Quality Gate passes.

---

### 133. Scenario: Jenkins build passed but Docker image scan failed. What will you do?

Answer:

I will check Trivy report and identify whether the vulnerability comes from base image, OS package, Java dependency, or installed package.

If fixable, update base image or dependency version.

If no fix exists, follow security exception process based on policy.

---

### 134. Scenario: Jenkins pushed image but deployment failed with ImagePullBackOff. What will you do?

Answer:

I will check image URI, image tag, ECR repository, AWS account, region, and ECR permissions.

Then I will verify image exists using:

aws ecr describe-images

Also check pod events using:

kubectl describe pod

---

### 135. Scenario: Deployment failed with CrashLoopBackOff. What will you do?

Answer:

I will check pod logs and events.

Commands:

kubectl logs <pod>

kubectl describe pod <pod>

I will verify environment variables, ConfigMap, Secret, database connectivity, application port, and startup errors.

If the issue is due to new version, rollback.

---

### 136. Scenario: Rollout status timed out. What will you do?

Answer:

I will check pods, deployment, events, and logs.

Common causes:

- readiness probe failure
- CrashLoopBackOff
- ImagePullBackOff
- insufficient resources
- missing config

If new version is faulty, rollback using kubectl rollout undo.

---

### 137. Scenario: Smoke test failed but pods are running. What will you check?

Answer:

I will check service, endpoints, ingress, ALB target health, path rules, application port, health endpoint, security groups, and logs.

Rollout success means pods are ready, but smoke failure means application is not reachable from user/service path.

---

### 138. Scenario: Production deployment failed. What will you do?

Answer:

First, check failure stage.

If rollout or smoke test failed, check pod logs, describe output, events, and deployment history.

If production impact exists, rollback to previous stable image using kubectl rollout undo or kubectl set image with previous Git SHA.

Then validate rollout and smoke test again and notify team.

---

### 139. Scenario: Jenkins job deployed wrong image tag. What will you do?

Answer:

I will stop or rollback deployment if required.

Then check pipeline parameters, image tag, build manifest, Jenkins logs, and ECR tag.

To prevent recurrence, print selected service, environment, image tag, image URI, AWS account, and require approval for higher environments.

---

### 140. Scenario: Jenkins deployed to wrong environment. What will you do?

Answer:

Immediately assess impact and rollback if needed.

Then check environment mapping, AWS identity, kubeconfig context, and parameters.

Use aws sts get-caller-identity and kubectl config current-context before deployment to prevent this.

---

### 141. Scenario: Jenkins pipeline is stuck at manual approval. What will you check?

Answer:

Check if authorized approver is available.

Check Jenkins input step.

Check submitter restriction.

Check whether pipeline timeout is configured.

Check if approval is waiting for stage/prod.

---

### 142. Scenario: Jenkins cannot assume AWS role. What will you check?

Answer:

Check:

- trust policy of target role
- source role permissions
- sts:AssumeRole permission
- role ARN
- external ID if used
- AWS account ID
- session name
- SCP or permission boundary

---

### 143. Scenario: Jenkins kubectl auth can-i fails. What will you check?

Answer:

Check:

- Kubernetes Role/RoleBinding
- ClusterRole/ClusterRoleBinding
- service account
- EKS aws-auth/access entry
- namespace
- IAM role mapping
- current context

---

### 144. Scenario: Jenkins pipeline works in dev but fails in prod. What will you check?

Answer:

Compare:

- AWS account/role
- IAM permissions
- EKS RBAC
- namespace
- ConfigMap/Secret
- ECR access
- network/Security Groups
- ingress/ALB config
- resource limits
- approval/change ticket

---

### 145. Scenario: Jenkins build fails because Maven dependencies cannot download. What will you check?

Answer:

Check:

- internet/Nexus access
- Maven settings.xml
- repository credentials
- proxy settings
- dependency version exists
- Maven cache corruption
- DNS/network issue

---

### 146. Scenario: Jenkins Docker build fails because JAR not found. What will you check?

Answer:

Check:

- Maven package completed
- target directory
- module path
- Dockerfile COPY path
- build context
- artifact name
- workspace path

---

### 147. Scenario: Jenkins agent pod deleted during build. What will you check?

Answer:

Check:

- pod events
- node pressure
- eviction
- memory limits
- CPU limits
- namespace quota
- cluster autoscaler
- image pull issue
- Jenkins Kubernetes plugin logs

---

### 148. Scenario: Jenkins reports are missing. What will you check?

Answer:

Check:

- report generated or not
- correct file path
- archiveArtifacts path
- JUnit report path
- workspace cleanup timing
- stage failure before report generation
- permissions

---

### 149. Scenario: Jenkins build successful but notification not sent. What will you check?

Answer:

Check:

- post block
- email plugin configuration
- SMTP server
- credentials
- recipient list
- network
- sender address
- failure/success condition

---

### 150. Scenario: Jenkins pipeline should not deploy two builds at the same time. How will you handle?

Answer:

Use:

disableConcurrentBuilds()

Also for deployment jobs, use locks or environment-level concurrency control.

Example:

lock(resource: "prod-auth-service")

---

## 13. Advanced Jenkins Questions

### 151. How do you improve Jenkins pipeline performance?

Ways:

- Use dependency caching
- Use parallel stages
- Use ephemeral agents
- Optimize Docker build context
- Use lighter images
- Avoid unnecessary full scans on every build
- Reuse Maven/npm cache
- Split CI and CD
- Use incremental builds where possible

---

### 152. How do you handle caching in Jenkins?

For Maven:

- local .m2 cache
- shared cache volume
- S3 tarball cache
- Nexus proxy repository

For npm:

- npm cache
- package-lock
- artifact caching

For Trivy:

- Trivy DB cache

---

### 153. What is parallel execution in Jenkins?

parallel allows stages to run at the same time.

Example:

parallel {
    stage('Unit Tests') { ... }
    stage('Lint') { ... }
}

Useful to reduce build time.

---

### 154. How do you handle Jenkins pipeline failures?

Steps:

1. Identify failed stage.
2. Check console logs.
3. Check reports.
4. Check agent status.
5. Check credentials and environment variables.
6. Reproduce command if needed.
7. Fix root cause.
8. Re-run pipeline.

---

### 155. How do you make Jenkins pipelines reusable?

Use:

- shared libraries
- parameters
- templates
- common functions
- environment maps
- standard stage names
- reusable Docker images
- folder-level configuration

---

### 156. How do you separate CI and CD pipelines?

CI pipeline runs on code changes and creates trusted image/artifact.

CD pipeline deploys selected image to target environment.

CI is code validation.

CD is environment deployment.

---

### 157. Why separate CI and CD?

Benefits:

- better control
- production approvals
- clear responsibility
- easier rollback
- promotion of same artifact
- avoids rebuilding for each environment
- supports release governance

---

### 158. What is Jenkins multibranch pipeline?

Multibranch pipeline automatically discovers branches and pull requests from Git.

Each branch can have its own Jenkinsfile.

Useful for feature branches, development, release, and main branches.

---

### 159. What is webhook in Jenkins?

Webhook triggers Jenkins job automatically when code is pushed or PR is created.

Example:

GitHub sends event to Jenkins.

Jenkins starts pipeline.

---

### 160. What if webhook is not triggering Jenkins?

Check:

- webhook URL
- Jenkins public accessibility
- GitHub/GitLab webhook logs
- credentials
- multibranch indexing
- branch source plugin
- Jenkins crumb/CSRF
- firewall
- SSL certificate

---

## 14. Final Must-Prepare Jenkins Scenario Questions

### 161. Explain your complete CI/CD pipeline.

Must include:

CI:
checkout, setup, scans, build, tests, SonarQube, package, image build, image scan, publish, notify.

CD:
select release, connect target AWS account/EKS, verify image, pre-checks, approval, kubectl deploy, rollout validation, smoke test, rollback, notify.

---

### 162. How do you deploy to multiple environments?

Same image is promoted.

Each environment has separate AWS account/EKS/config.

Jenkins selects target environment, assumes role, updates kubeconfig, and deploys using kubectl.

---

### 163. How do you rollback deployment?

Use kubectl rollout undo or kubectl set image with previous Git SHA.

Then validate rollout and smoke tests.

---

### 164. How do you manage Jenkins secrets?

Use Jenkins Credentials Manager and withCredentials.

Prefer IAM roles for AWS.

Use AWS Secrets Manager or Parameter Store for application runtime secrets.

---

### 165. How do you handle production approval?

Use Jenkins input step with submitter restriction and change ticket validation.

---

### 166. How do you troubleshoot failed Jenkins build?

Check failed stage, logs, reports, agent status, credentials, environment variables, and reproduce command if needed.

---

### 167. How do you secure Jenkins?

RBAC, credentials manager, least privilege, updated plugins, HTTPS, no builds on controller, agent isolation, secret masking, backups.

---

### 168. How do you manage Jenkins disk space?

Use buildDiscarder, cleanWs, archive only required artifacts, prune Docker images, use ephemeral agents, monitor JENKINS_HOME.

---

### 169. How do you handle image vulnerability failures?

Check scan report, identify base image or package, update base image/dependency, or follow exception process.

---

### 170. How do you ensure traceability in Jenkins?

Use Git SHA tag, build number, image URI, image digest, scan reports, build manifest, Jenkins URL, deployment notification.

---

# Final Short Interview Answer for Jenkins Experience

In our project, Jenkins was used for both CI and CD.

For CI, Jenkins checked out the code, prepared environment variables, scanned secrets using Gitleaks, scanned dependencies using OWASP Dependency-Check, built the Java Spring Boot application using Maven, ran unit tests, performed SonarQube analysis and Quality Gate validation, ran integration tests, packaged the JAR, built a Docker image using Git SHA tag, scanned the image using Trivy, pushed it to ECR, archived reports, and notified the team.

For CD, Jenkins picked the Git SHA image from ECR and deployed it to dev, test, stage, and prod. Since each environment had a separate AWS account, Jenkins connected to the target account using environment-specific IAM role, updated kubeconfig for the EKS cluster, verified the image, performed Kubernetes pre-checks, used manual approval for stage/prod, deployed using kubectl set image, validated rollout, ran smoke tests, and notified the team.

If deployment failed, rollback was done using kubectl rollout undo or by redeploying the previous stable Git SHA image.

We managed secrets using Jenkins Credentials Manager and withCredentials, preferred IAM roles/IRSA for AWS access, and kept application runtime secrets in AWS Secrets Manager or Parameter Store.
