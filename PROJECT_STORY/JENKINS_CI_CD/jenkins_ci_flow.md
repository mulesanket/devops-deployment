# Jenkins CI Pipeline Flow — Java Spring Boot Application

In our project, we had a Jenkins-based CI pipeline for Java/Spring Boot services. The purpose of the CI pipeline was to validate every code change before it moved toward deployment.

The pipeline was designed to fail fast, run security and quality checks early, build and test the application, create a versioned artifact, build and scan the container image, publish trusted outputs, and finally notify the team.

---

## Stage 1 — Source Checkout

The first stage was Source Checkout.

In this stage, Jenkins pulled the source code from Git based on the branch or pull request that triggered the pipeline. Along with the source code, Jenkins also captured important Git metadata such as branch name, commit SHA, commit message, repository URL, and workspace path.

This metadata was important because the Git SHA was used later for Docker image tagging, artifact naming, scan reports, build manifest, and deployment traceability.

So, this stage was not just about cloning the repository. It established the source-code identity for the entire CI flow.

---

## Stage 2 — Environment Setup

After checkout, the next stage was Environment Setup.

In this stage, Jenkins prepared all runtime variables required by the pipeline. It resolved values such as service name, branch name, Git SHA, AWS account ID, AWS region, ECR repository URL, image tag, image URI, artifact path, and report path.

We also validated AWS identity using:

    aws sts get-caller-identity

This confirmed whether Jenkins was running with the expected AWS role or credentials.

This stage was important because later stages like image build, ECR push, S3 upload, and report publishing depended on these values. If the AWS role, region, account, repository, or image tag was wrong, the pipeline could fail later after wasting build time.

So, this stage acted as a fail-fast validation stage.

---

## Stage 3 — Secret Scanning

After environment setup, we had Secret Scanning.

We used Gitleaks for this stage.

The purpose of this stage was to detect hardcoded secrets such as AWS access keys, API tokens, private keys, database passwords, certificates, and other sensitive values in the source code.

This stage was kept early because it is fast and security-critical. If a real secret was detected, the pipeline failed immediately.

For real secrets, the fix was not only to remove the secret from the code. The exposed credential also had to be rotated because it could already exist in Git history or Jenkins logs.

This helped us shift security left and prevent credentials from moving further into the build and deployment process.

---

## Stage 4 — Dependency Vulnerability Scan / SCA

After secret scanning, we had the Dependency Vulnerability Scan stage.

We used OWASP Dependency-Check for this stage.

This stage scanned Java/Maven dependencies for known vulnerabilities. It checked project dependencies from files like pom.xml and the Maven dependency tree.

The purpose was to identify CVEs in third-party libraries before the application was built and packaged.

This was important because even if our application code is secure, vulnerable third-party libraries can still create production risk. For Java/Spring Boot services, many libraries come directly or transitively through Spring Boot starters, Jackson, Logback, database drivers, and other dependencies.

So, this stage helped us block known vulnerable dependencies early in the CI process.

---

## Stage 5 — Build / Compile

After the early security checks passed, the next stage was Build / Compile.

We used Maven for this stage.

In this stage, Jenkins compiled the Java Spring Boot application and validated whether the code was syntactically correct and whether dependencies could be resolved properly.

A typical command for the logical compile stage was:

    mvn clean compile

In some pipelines, Maven commands are combined using:

    mvn clean verify

But logically, we explain build, unit test, integration test, and package as separate CI stages.

This stage ensured that only compilable code moved forward to testing and quality checks.

---

## Stage 6 — Unit Testing

After the build stage, Jenkins ran Unit Tests.

We used JUnit with Maven Surefire Plugin.

The purpose of this stage was to validate individual classes, methods, service logic, validations, utility methods, controller logic, and exception handling.

Unit tests were fast and isolated. External dependencies such as databases, APIs, queues, or third-party services were mocked.

Jenkins published the Surefire XML reports using the Jenkins JUnit report publisher. This helped developers quickly identify failed test cases from the Jenkins build page.

This stage helped catch application logic issues before integration testing, packaging, and image creation.

---

## Stage 7 — SonarQube Analysis and Quality Gate

After unit testing, we ran SonarQube Analysis and Quality Gate validation.

We used SonarQube for static code analysis and code quality checks.

SonarQube checked bugs, vulnerabilities, security hotspots, code smells, duplicate code, complexity, maintainability issues, and test coverage.

We placed SonarQube after unit tests because for Java/Spring Boot projects, SonarQube can consume compiled code, JUnit test reports, and JaCoCo coverage reports. This gives a more complete code quality and coverage view.

After analysis, Jenkins waited for the SonarQube Quality Gate result. If the Quality Gate failed, the pipeline stopped and did not move to packaging or image build.

This stage enforced code quality and security standards before creating deployable artifacts.

---

## Stage 8 — Integration Testing

After SonarQube Quality Gate, we had Integration Testing.

Integration tests validated whether multiple parts of the application worked together correctly.

For Java/Spring Boot services, this included flows such as API layer, service layer, repository layer, database interaction, authentication flow, and external service integration.

Unit tests use mocks and test small pieces of logic. Integration tests use real or realistic dependencies such as a test database, test profile, mock server, Docker Compose setup, Testcontainers, or a test environment.

For Maven projects, integration tests were usually handled using Maven Failsafe and executed during the verify phase.

A common command was:

    mvn verify

This stage helped catch issues that unit tests cannot catch, especially configuration, database, API, and dependency-related issues.

---

## Stage 9 — Package / Archive Artifact

After all validation stages passed, Jenkins packaged the application artifact.

We used Maven for packaging.

For Java/Spring Boot services, the artifact was usually a JAR file generated under the target directory.

A common command was:

    mvn package -DskipTests

Tests were skipped at this stage because unit tests and integration tests had already run earlier.

This stage followed the build once, deploy many principle. That means one artifact was created from a specific Git commit and the same artifact was used for image creation and promotion across environments.

The JAR artifact was archived in Jenkins or uploaded to an artifact store such as S3, Nexus, or Artifactory. Metadata like service name, Git SHA, branch, build number, and timestamp was used for traceability.

---

## Stage 10 — Container Image Build

After packaging the Java artifact, Jenkins built the container image.

In this stage, the tested JAR file was copied into a Docker image using an optimized Dockerfile.

Since Jenkins agents were running on Kubernetes, Kaniko could be used to build the image without requiring Docker daemon or privileged Docker-in-Docker access.

The image was tagged using the Git SHA instead of relying only on latest. This gave traceability from source code to Jenkins build to container image.

We also followed container image best practices such as using a lightweight runtime base image, copying only the required JAR, avoiding build tools in the runtime image, running the application as a non-root user, and adding labels like Git SHA, branch, build number, and service name.

This stage converted the tested application artifact into a versioned container image.

---

## Stage 11 — Container Image Vulnerability Scan

After building the Docker image, Jenkins scanned the final container image.

We used Trivy for this stage.

This scan checked the final runtime image for known vulnerabilities in OS packages, Java runtime layers, and application libraries present inside the image.

This stage was important because OWASP Dependency-Check scans application dependencies from the source side, but the final Docker image may also contain vulnerabilities from the base image or operating system packages.

The scan usually focused on high and critical vulnerabilities. Depending on policy, the pipeline failed for fixable high or critical CVEs.

This stage ensured that only scanned and trusted images moved forward to publishing.

---

## Stage 12 — Publish / Push

After the image was built and scanned, Jenkins published the CI outputs.

The container image was pushed to Amazon ECR using the Git SHA based tag.

We avoided relying only on latest because Git SHA based tagging gave clear traceability between source code, Jenkins build, container image, and deployment.

Along with the image, Jenkins also published or archived build artifacts and reports such as the JAR file, unit test reports, integration test reports, dependency scan report, image scan report, SonarQube result link, and build manifest.

Artifacts and reports were archived in Jenkins or uploaded to a central artifact store such as S3, Nexus, or Artifactory. Jenkins supports archiving generated files such as JARs and reports with archiveArtifacts, and publishing test reports with JUnit report steps. :contentReference[oaicite:1]{index=1}

The build manifest was important because it recorded service name, branch, Git SHA, build number, image URI, image digest, artifact path, report locations, and timestamp.

This helped during audit, rollback, and production troubleshooting.

---

## Stage 13 — Report / Notify

The final CI stage was Report and Notify.

After publishing the image, artifacts, and reports, Jenkins sent an Outlook email notification to the development, QA, and DevOps teams.

The email included build status, service name, branch, Git SHA, Jenkins build number, image URI, artifact location, report links, and Jenkins build URL.

If the build failed, the notification helped the team quickly identify where to check logs and reports.

If the build passed, it confirmed that the code had completed CI validation and the trusted artifact/image was available for the next deployment process.

---

## Post-Build Action — Cleanup If Required

Cleanup was not considered a main CI stage.

It was handled as a Jenkins post-build action if required.

For static Jenkins agents such as EC2 instances or long-running VMs, cleanup was important because old workspaces, temporary files, Docker images, dangling layers, Maven cache issues, and scan reports could slowly fill disk space.

For Kubernetes-based ephemeral Jenkins agents, cleanup was less critical because the build pod was destroyed after completion. Still, temporary files or sensitive scan outputs could be cleaned after reports and artifacts were archived.

So the main CI flow ended with Report and Notify, and cleanup was handled separately as a post-build action.

---

## Final CI Flow

Source Checkout
→ Environment Setup
→ Secret Scanning
→ Dependency Vulnerability Scan / SCA
→ Build / Compile
→ Unit Testing
→ SonarQube Analysis and Quality Gate
→ Integration Testing
→ Package / Archive Artifact
→ Container Image Build
→ Container Image Vulnerability Scan
→ Publish / Push
→ Report / Notify

Cleanup: Jenkins post-build action if required.
