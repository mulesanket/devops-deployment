# Jenkins CI Pipeline Interview Story — AWS DevOps Role

## Overview

In our project, we had a Jenkins-based CI pipeline for Java/Spring Boot services. The purpose of the CI pipeline was to validate every code change before it moved toward deployment.

The pipeline was not only responsible for building the application. It also performed security checks, dependency scanning, unit testing, code quality analysis, integration testing, artifact packaging, container image creation, image scanning, publishing, and final notification.

The main goal was to fail fast, maintain security gates, build a trusted artifact, scan the final image, publish traceable outputs, and notify the team with proper reports.

---

## Stage 1 — Source / Checkout

### What this stage does

The first stage in our CI pipeline was Source Checkout.

In this stage, Jenkins checked out the application source code from the configured Git repository. The pipeline resolved important Git metadata such as branch name, commit SHA, repository URL, and workspace path.

This stage was important because every later stage such as secret scanning, dependency scanning, build, unit testing, SonarQube analysis, packaging, image build, and publishing depends on the exact source code version checked out in this stage.

### Why this stage comes first

Source checkout comes first because the pipeline cannot scan, build, test, package, or containerize anything without having the correct source code in the Jenkins workspace.

The Git commit SHA generated from this stage also becomes the identity of the build. We used that SHA later for image tagging, artifact naming, scan reports, and deployment traceability.

### What happens technically

1. Jenkins receives a trigger from Git webhook, branch indexing, pull request, or manual build.
2. Jenkins allocates a workspace on the build agent.
3. Jenkins authenticates to Git using stored Jenkins credentials.
4. Jenkins fetches the required branch, PR, tag, or commit.
5. Jenkins checks out the exact revision into the workspace.
6. Jenkins captures metadata like branch name, commit SHA, repository URL, and workspace path.
7. These values are reused in later stages for traceability.

### What I worked on

In this stage, I worked on ensuring that Jenkins was checking out the correct branch and commit for each build.

I also used Git metadata such as commit SHA and branch name in later stages for image tagging, artifact naming, scan reports, and build traceability.

For example, instead of tagging container images only as latest, we used the Git commit SHA so that every image could be mapped back to the exact source code version.

### Real-time issues

One common issue was Jenkins building the wrong branch or old commit. In that case, I checked the Jenkins console logs to confirm the branch name, commit SHA, and refspec. I also verified the multibranch pipeline configuration and webhook trigger.

Another issue was Git authentication failure due to expired credentials or missing repository access. I checked the Jenkins credentials ID, repository URL, and whether the service account or token had access to the repository.

### Interview summary

The Source Checkout stage pulls the correct source code version into Jenkins workspace and establishes the commit identity for the entire CI pipeline.

---

## Stage 2 — Environment Setup

### What this stage does

Environment Setup prepares the CI job with all required runtime values before actual scanning, build, test, or image creation starts.

The purpose of this stage was to fail fast before wasting build time.

In this stage, Jenkins validated that the build agent was running with the correct AWS identity and that all computed variables were generated correctly before the pipeline moved forward.

We used values like service name, branch name, Git SHA, AWS account ID, AWS region, ECR repository name, image tag, image URI, artifact bucket, and report paths throughout the pipeline.

These values were important for image tagging, artifact naming, scan reports, publishing, and deployment traceability.

### Why this stage comes after checkout

Environment setup comes after checkout because the pipeline first needs source context like branch name and commit SHA.

It comes before scanning and build because we want to fail early if Jenkins is using the wrong AWS credentials, wrong region, missing variables, incorrect ECR repository, or invalid service configuration.

### What happens technically

1. Jenkins reads branch name and Git commit SHA from the checkout stage.
2. Pipeline calculates a short Git SHA for image tagging.
3. Pipeline validates AWS identity using aws sts get-caller-identity.
4. Pipeline builds the ECR repository URL.
5. Pipeline prepares image tag and image URI.
6. Pipeline sets artifact bucket or artifact repository paths.
7. Pipeline prints key non-sensitive values in logs for traceability.
8. Pipeline fails early if mandatory variables or authentication are missing.

### Example values

    Service     : school-spider-api
    Branch      : development
    Git SHA     : a1b2c3d
    AWS Account : 123456789012
    AWS Region  : eu-west-2
    ECR Repo    : 123456789012.dkr.ecr.eu-west-2.amazonaws.com/school-spider-api
    Image URI   : 123456789012.dkr.ecr.eu-west-2.amazonaws.com/school-spider-api:a1b2c3d

### What I worked on

In the Environment Setup stage, I worked on validating that the Jenkins agent was running with the correct AWS identity and that all computed variables were generated correctly.

This stage helped us catch configuration issues early, such as wrong AWS credentials, incorrect region, missing repository name, incorrect image tag, or wrong artifact path.

### Real-time issues

One common issue was Jenkins running with the wrong AWS identity. The pipeline would later fail during ECR push or S3 upload because the role did not have correct permissions.

To troubleshoot, I used:

    aws sts get-caller-identity

This helped confirm the AWS account ID and IAM role used by Jenkins.

Another common issue was wrong AWS region. For example, the ECR repository existed in one region, but the pipeline was configured with another region. I checked the AWS_REGION variable, ECR repository region, and image URI.

### Interview summary

Environment Setup validates credentials and prepares all computed variables required for the rest of the CI pipeline.

---

## Stage 3 — Secret Scanning

### Tool

Gitleaks

### What this stage does

Secret Scanning checks whether developers accidentally committed sensitive data into the repository.

This stage scans the source code for hardcoded passwords, API keys, AWS access keys, private keys, database credentials, tokens, certificates, and other sensitive values.

In our CI pipeline, this stage was kept very early because it is fast and security-critical. If any real secret was found, the pipeline failed immediately.

### Why this stage comes early

Secret scanning comes before build and test because there is no point in compiling or packaging code that already contains exposed credentials.

It is a fail-fast security gate.

### What I worked on

We used Gitleaks in our CI pipeline to detect hardcoded secrets such as AWS access keys, API tokens, private keys, passwords, and certificates before the build stage.

If Gitleaks detected a secret, the pipeline failed immediately. I checked the generated report, identified the file path and line number, and verified whether it was a real secret or a false positive.

If it was a real secret, we asked the developer to remove it from the repository, rotate the exposed credential, and store it securely in AWS Secrets Manager, Parameter Store, Jenkins credentials, or another approved secrets management solution.

### Full repo scan or latest commit scan

For pull request builds, scanning only the changed files or PR diff is faster.

For protected branches such as development, release, or main, scanning the full checked-out service directory gives better confidence before producing an artifact.

For full Git history scanning, a separate scheduled scan can be used because scanning the entire Git history on every CI build can increase build time.

### Real-time issue

One issue we handled was when a developer accidentally committed a credential-like value in a config file or test file.

The secret scan failed the pipeline. I checked the Gitleaks report, identified the file and line number, and verified whether it was a real secret or dummy test value.

If it was a real secret, simply removing it from the latest commit was not enough. We also had to rotate the credential because it may already exist in Git history or Jenkins logs.

### Interview summary

Secret scanning prevents hardcoded credentials and sensitive values from entering the build and deployment flow.

---

## Stage 4 — Dependency Vulnerability Scan / SCA

### Tool

OWASP Dependency-Check

### What this stage does

After secret scanning, we had a dependency vulnerability scanning stage using OWASP Dependency-Check.

This stage scanned third-party libraries used by the Java/Spring Boot application, mainly from files like pom.xml and the Maven dependency tree.

The purpose was to identify known CVEs in open-source dependencies before the application was built, packaged, and deployed.

### Why this stage is important

In Java/Spring Boot applications, most projects use many third-party libraries such as Spring Boot starters, Jackson, Logback, Apache Commons, database drivers, security libraries, testing libraries, and internal shared libraries.

Even if our application code is clean, a vulnerable third-party dependency can still create security risk. So we used SCA scanning to catch known vulnerabilities in dependencies.

SAST checks our own source code.

SCA checks third-party libraries used by the application.

### What I worked on

In the SCA stage, we used OWASP Dependency-Check to scan Java/Maven dependencies for known vulnerabilities.

My responsibility was to support the Jenkins integration, verify that dependency reports were generated correctly, archive the reports, and help troubleshoot failures when the scan detected high or critical CVEs.

If the pipeline failed due to a vulnerable dependency, I checked the report to identify the dependency name, version, CVE ID, severity, CVSS score, and whether a fixed version was available.

Then I coordinated with the development team to upgrade the dependency or review the finding if it was a false positive.

### Real-time issue

One common issue was a high CVE reported in a transitive dependency.

The application may not directly define that vulnerable library in pom.xml, but it can come through another dependency like a Spring Boot starter or third-party SDK.

In that case, I checked the Maven dependency tree using:

    mvn dependency:tree

Then I identified which parent dependency was bringing the vulnerable version.

Based on that, the development team either upgraded the parent dependency, upgraded the Spring Boot BOM, overrode the vulnerable dependency version using dependencyManagement, or applied an approved suppression only if it was a confirmed false positive.

### Interview summary

SCA scans third-party dependencies and blocks known vulnerable libraries before packaging and deployment.

---

## Stage 5 — Build / Compile

### Tool

Maven

### What this stage does

The Build / Compile stage compiles the Java Spring Boot source code using Maven.

It validates whether the code is syntactically correct, whether dependencies can be resolved, and whether the application can be successfully compiled.

If there are compilation errors, missing dependencies, incorrect imports, Java version mismatch, or Maven configuration issues, the pipeline fails at this stage.

### Common command

For logical build/compile stage:

    mvn clean compile

In actual Jenkins implementation, some teams use:

    mvn clean verify

But logically, build, unit test, integration test, and package are explained as separate CI stages for clarity.

### Why this stage comes after security checks

Secret scan and dependency scan are fail-fast security gates.

Once those early checks pass, we run Maven build to compile the application and prepare it for testing and packaging.

### What I worked on

In the Build stage, we used Maven to compile the Java Spring Boot application.

My responsibility was to support the Jenkins build execution, check Maven logs when builds failed, validate Java and Maven versions, verify dependency resolution issues, and coordinate with developers for compilation failures.

For multi-module services, we made sure the correct module was being built and that required dependent modules were also included in the build.

### Real-time issues

One common issue was compilation failure due to missing imports, incorrect method signatures, class not found errors, or code changes breaking another module.

I checked the Maven console output, identified the file and line number, and shared the error with the development team.

Another common issue was dependency resolution failure. I checked whether the dependency version existed, whether the internal Nexus or Artifactory repository was reachable, whether settings.xml was configured correctly, and whether credentials were valid.

Another issue was Java version mismatch. For example, the project required Java 17, but the Jenkins agent was running Java 11. I checked:

    mvn -v
    java -version

Then updated the Jenkins agent image or tool configuration.

### Interview summary

Build stage compiles the Java Spring Boot application and verifies that the source code and dependencies are valid.

---

## Stage 6 — Unit Test

### Tools

JUnit
Maven Surefire Plugin
Jenkins JUnit Report Publisher

### What this stage does

The Unit Test stage runs fast, isolated tests for individual classes, methods, or business logic.

For Java/Spring Boot applications, these tests are usually written using JUnit and executed through Maven Surefire during the Maven test phase.

The goal is to validate application logic before integration testing, packaging, container image creation, and deployment.

Build tells us whether the code compiles.

Unit tests tell us whether the logic works.

### Common command

    mvn test

### Unit test characteristics

Unit tests test small pieces of code in isolation.

External dependencies like database, REST APIs, queues, caches, or third-party services are usually mocked.

They are fast and should not depend on network or external systems.

### What I worked on

In the Unit Test stage, we executed JUnit test cases using Maven Surefire.

My responsibility was to ensure the Jenkins pipeline executed the test phase properly and published test reports in Jenkins.

If unit tests failed, I checked the Jenkins console logs and Surefire reports to identify which test class and test method failed.

I coordinated with developers when failures were related to code logic, assertions, mocking issues, or test data problems.

### Real-time issues

One common issue was a failed JUnit test due to changed business logic or incorrect assertion.

I checked the failed test name, error message, stack trace, and Surefire report.

Sometimes tests passed on a developer laptop but failed in Jenkins. In that case, I compared Java version, Maven version, active Spring profile, environment variables, timezone, file path case sensitivity, and test data assumptions.

Another issue was no test report being generated. I checked whether target/surefire-reports existed and whether the Jenkins report path pattern was correct.

### Interview summary

Unit tests validate application logic in isolation before the code moves to integration testing and packaging.

---

## Stage 7 — SonarQube Analysis + Quality Gate

### Tool

SonarQube

### What this stage does

After unit tests, we ran SonarQube analysis for static code analysis, code quality, security hotspots, bugs, vulnerabilities, code smells, duplication, complexity, and coverage validation.

SonarQube generated a project-level code quality report and then the Jenkins pipeline waited for the SonarQube Quality Gate result.

If the Quality Gate failed, the pipeline stopped and did not proceed to packaging, image build, or deployment-related stages.

### Why this stage comes after unit tests

For Java/Spring Boot projects, SonarQube is better placed after build and unit tests because it can use compiled code, JUnit test reports, and JaCoCo coverage reports.

If we run SonarQube too early, we may miss useful coverage and test-related metrics.

### What SonarQube checks

SonarQube checks:

1. Bugs
2. Vulnerabilities
3. Security hotspots
4. Code smells
5. Duplicate code
6. Complexity
7. Maintainability issues
8. Reliability issues
9. Unit test coverage
10. Quality Gate status

### What I worked on

In this stage, we integrated SonarQube with Jenkins for static analysis and quality gate validation.

My responsibility was to make sure the SonarQube scan ran successfully, validate project keys and scanner configuration, check quality gate failures, and coordinate with developers when issues were reported.

If the Quality Gate failed, I checked the SonarQube dashboard to identify the exact issue, severity, rule, file path, and line number.

Based on that, the development team fixed bugs, code smells, vulnerabilities, duplication, or coverage issues before the pipeline moved forward.

### Real-time issues

One common issue was Quality Gate failure due to low coverage, duplicate code, blocker issues, critical bugs, or new code smells.

I opened the SonarQube dashboard, checked the failed condition, identified whether it was related to coverage, bugs, vulnerabilities, or code smells, and shared the exact file and rule details with the developer team.

Sometimes unit tests passed in Jenkins, but SonarQube showed 0% coverage. In that case, I checked whether the JaCoCo report was generated, whether the XML report path was correct, and whether the Maven command was executed from the correct module or root directory.

### Interview summary

SonarQube validates source code quality, static security issues, duplication, and coverage before the application is packaged.

---

## Stage 8 — Integration Test

### Tools

JUnit
Maven Failsafe Plugin
Spring Boot Test Profile
Testcontainers or test environment, if required

### What this stage does

The Integration Test stage validates whether different parts of the application work together correctly.

Unlike unit tests, integration tests do not test only one method or class in isolation. They test application flow with real or realistic dependencies such as database, message queue, REST API, cache, or another service mock.

For Java/Spring Boot services, integration tests were used to validate whether APIs, service layer, repository layer, database interaction, and external service integration were working correctly before packaging the artifact and building the container image.

### Why this stage comes after unit test and SonarQube

Integration tests are heavier than unit tests because they may need Spring application context, database, test containers, or dependent services.

So we first ran fast checks like unit tests and SonarQube Quality Gate. Only after those passed did we run integration tests.

### Common command

    mvn verify

### Unit test vs integration test

Unit Test:

- Tests small pieces of code in isolation
- External dependencies are mocked
- Fast execution

Integration Test:

- Tests whether multiple components work together
- May connect to database, message queue, external API mock, Docker Compose service, Testcontainers, or test environment
- Slower than unit tests but catches configuration and dependency issues

### What I worked on

In the Integration Test stage, I supported execution of integration tests in Jenkins for Java/Spring Boot services.

My responsibility was to make sure integration tests were triggered correctly, test reports were published, required test profiles or environment variables were available, and failures were analyzed from Jenkins logs and Failsafe reports.

If integration tests failed, I checked whether the issue was with application logic, test data, database connectivity, Spring profile, environment variable, dependent service, or timeout.

### Real-time issues

One common issue was integration test failure because the test database was not reachable or the DB connection properties were incorrect.

I checked the active Spring profile, database URL, credentials source, network connectivity, and whether the test database or container was started correctly.

Sometimes integration tests failed because the Spring Boot application context did not start. I checked missing environment variables, incorrect application-test.yml values, bean creation errors, profile mismatch, and missing mock configuration for external services.

### Interview summary

Integration tests validate that application components work together correctly with real or realistic dependencies.

---

## Stage 9 — Package / Archive Artifact

### Tool

Maven

### Artifact storage

Jenkins archiveArtifacts
S3 / Nexus / Artifactory

### What this stage does

The Package / Archive Artifact stage creates the deployable application artifact after the code has passed build, unit test, SonarQube Quality Gate, and integration test stages.

For a Java/Spring Boot application, this artifact is usually a JAR file or sometimes a WAR file, generated under the target directory.

This artifact becomes the build output that is later used for Docker image creation or stored in an artifact repository.

### Common command

    mvn package -DskipTests

Tests are skipped here because they were already executed in earlier stages.

### Build once, deploy many

In production CI/CD, we follow the build once, deploy many principle.

That means we create one artifact from a specific Git commit and use the same artifact for dev, test, staging, and production deployments.

We should not rebuild the application separately for every environment because that can create differences between environments.

The artifact promoted to production should be the same artifact that was tested earlier.

### What I worked on

In the Package Artifact stage, we used Maven to create the final JAR artifact for the Java/Spring Boot service.

My responsibility was to make sure the correct artifact was generated, validate the target path, archive the artifact in Jenkins or upload it to the configured artifact storage, and ensure artifact names or metadata included Git SHA and build number for traceability.

This artifact was then used as input for the Docker image build stage.

### Real-time issues

One issue was the pipeline moving to artifact publishing, but the JAR file was not found in the expected target directory.

I checked whether Maven package completed successfully, whether the module path was correct, whether packaging type was jar or war in pom.xml, and whether the Jenkins artifact path matched the actual target directory.

Sometimes multiple JARs were present, such as the main JAR, sources JAR, javadoc JAR, or original JAR. I made sure the pipeline picked the executable Spring Boot JAR and excluded sources or javadoc artifacts.

### Interview summary

Package stage creates a versioned deployable JAR/WAR artifact from already validated code.

---

## Stage 10 — Container Image Build

### Tool

Kaniko or Docker Build

For Kubernetes-based Jenkins agents, Kaniko is preferred because it can build images without requiring Docker daemon or privileged Docker-in-Docker setup.

### What this stage does

The Container Image Build stage converts the tested and packaged Java/Spring Boot artifact into a Docker or OCI container image.

For our Java/Spring Boot service, the JAR generated by Maven was copied into a runtime base image using a Dockerfile.

Then the image was tagged with the Git SHA and prepared for pushing to Amazon ECR.

### Why this stage comes after package

Container image build comes after package because the Docker image should contain the already validated application artifact.

We should not build the image from untested or partially built source code.

First we validate the code, then package it, and then containerize it.

### Dockerfile practices

In production, we preferred a lightweight runtime image like JRE instead of a full JDK where possible.

We copied only the final JAR into the image, ran the application as a non-root user, exposed the application port, and used a clear entrypoint.

This helped reduce image size and improve container security.

### Container image build practices

In container image build, we followed these practices:

1. Used Git SHA based immutable image tags.
2. Avoided relying only on latest.
3. Used lightweight runtime base images.
4. Avoided keeping Maven, source code, or build tools in the runtime image.
5. Ran the application as a non-root user.
6. Added image labels like Git SHA, branch, build number, and service name.
7. Kept Dockerfile and build context clean.
8. Used .dockerignore to avoid copying unnecessary files.

### What I worked on

In the Container Image Build stage, we built Docker images for Java/Spring Boot services.

My responsibility was to support the Dockerfile build process, validate image tagging, check build context issues, troubleshoot build failures, and ensure the image was built using the correct JAR artifact.

We used Git SHA based image tags instead of relying only on latest, so every image could be traced back to a specific commit and Jenkins build.

### Real-time issues

One common issue was Docker or Kaniko failing because the JAR file was not present at the expected path.

I checked whether Maven package completed successfully, verified the target directory, checked the Dockerfile COPY path, and confirmed the build context.

Another issue was wrong build context. The Dockerfile path was correct, but the Dockerfile could not access the target JAR because the build context was wrong. I checked the build context path and Dockerfile path to make sure they matched the repository structure.

### Interview summary

Container image build converts the tested Java artifact into a versioned Docker/OCI image ready for scanning and publishing.

---

## Stage 11 — Container Image Vulnerability Scan

### Tool

Trivy

### What this stage does

After the container image is built, we scan the final image using Trivy.

This stage checks whether the image contains known vulnerabilities in OS packages, runtime libraries, and application dependencies present inside the final image.

OWASP Dependency-Check scans application dependencies from the source/build side.

Trivy image scan checks the final container image, including base image OS packages such as Alpine, Debian, Ubuntu, Amazon Linux packages, Java runtime layers, and libraries bundled inside the image.

### Why this stage comes after image build

Image scanning comes after container image build because we need the final image first.

The final image may contain vulnerabilities from the base image layer, OS packages, Java runtime, or copied application artifact. These may not be visible during source dependency scanning.

SCA tells us whether our application dependencies are vulnerable.

Image scanning tells us whether the final runtime image is safe enough to publish or deploy.

### What I worked on

In the Container Image Scan stage, we used Trivy to scan the final Docker image before publishing or promoting it.

My responsibility was to make sure the scan ran against the correct image tag, reports were archived, and the pipeline failed for fixable high or critical vulnerabilities based on our security gate.

If the scan failed, I checked whether the CVE came from the application dependency, base image OS package, Java runtime, or some package installed in the Dockerfile.

### Real-time issues

One common issue was Trivy reporting high or critical vulnerabilities from the base image.

For example, if the image used an older JRE or Linux base image, Trivy could detect vulnerabilities in OS packages.

In that case, I checked whether a newer base image was available and suggested updating the Dockerfile base image.

Sometimes Trivy reported vulnerabilities where no fixed package version was available. In such cases, we reviewed them separately based on security policy.

Another issue was image scan failure due to registry authentication or image tag not found. I checked whether the image existed, whether the image URI was correct, whether Jenkins had registry access, and whether the correct AWS region and ECR repository were used.

### Interview summary

Trivy image scan validates the final container image for high and critical vulnerabilities before publishing or deployment.

---

## Stage 12 — Publish / Push

### Tools

Amazon ECR for container images
S3 / Nexus / Artifactory for build artifacts and reports
Jenkins archiveArtifacts for Jenkins-side retention

### What this stage does

After the image build and image scan stages, we had the Publish / Push stage.

In this stage, the container image was pushed to Amazon ECR using a Git SHA based tag.

We avoided relying only on latest because Git SHA based tagging gives clear traceability between source code, Jenkins build, container image, and deployment.

Along with the image, we also published build artifacts and reports such as the JAR file, unit test reports, integration test reports, dependency scan reports, image scan reports, SonarQube result link, and build manifest.

These were archived in Jenkins or uploaded to a central artifact store like S3, Nexus, or Artifactory.

### What is build manifest?

The build manifest was important because it recorded:

1. Service name
2. Branch
3. Git SHA
4. Jenkins build number
5. Build URL
6. Image URI
7. Image digest
8. Artifact path
9. Report locations
10. Timestamp

This helped during audits, rollback, and production troubleshooting.

### What I worked on

In the Publish stage, I supported pushing the built container image to Amazon ECR and publishing build outputs such as JAR files, test reports, vulnerability scan reports, and build metadata.

My responsibility was to validate whether the image was pushed with the correct Git SHA tag, verify the image digest from ECR, ensure reports were archived or uploaded to the correct artifact location, and troubleshoot IAM or repository permission issues.

This stage helped maintain traceability between Git commit, Jenkins build, container image, scan reports, and deployment.

### Real-time issues

One common issue was image push failure to ECR due to missing IAM permissions or authentication problems.

I checked:

    aws sts get-caller-identity

Then I verified the IAM role used by Jenkins, checked ECR permissions such as ecr:PutImage, confirmed the repository existed, and validated the image URI and AWS region.

Another issue was image being pushed to the wrong ECR repository or wrong AWS account due to incorrect environment variables. I verified AWS account ID, region, ECR repo name, image URI, and Git SHA tag printed during the Environment Setup stage.

If artifact upload to S3, Nexus, or Artifactory failed, I checked storage path, credentials or IAM role, bucket/repository policy, network access, and whether the file existed before upload.

### Interview summary

Publish stage stores the trusted image, artifact, reports, and metadata so downstream deployment systems can deploy the exact validated build.

---

## Stage 13 — Report / Notify

### Tool

Outlook email notification using Jenkins Email Extension Plugin

### What this stage does

The final CI stage was Report and Notify.

After publishing the image, artifacts, and reports, Jenkins sent an Outlook email notification to the development, QA, and DevOps teams.

The email included build status, service name, branch, Git SHA, Jenkins build number, image URI, artifact location, report links, and Jenkins build URL.

This helped the team quickly understand whether the build passed or failed and where to check logs or reports if any stage failed.

### Why this is the last CI stage

Report and Notify comes last because the final pipeline result is available only after all validation, build, scan, package, and publish stages are completed.

Only after that can Jenkins send a meaningful notification with the correct build status and report links.

### Notification details

The Outlook email usually included:

1. Job name
2. Service name
3. Branch name
4. Git SHA
5. Jenkins build number
6. Build status
7. Failed stage if build failed
8. Jenkins build URL
9. Test report link
10. SonarQube result/link
11. Dependency scan report
12. Image scan report
13. Image URI
14. Artifact location
15. Timestamp

### What I worked on

In the final Report and Notify stage, I supported publishing build summaries and sending Outlook email notifications to the team.

My responsibility was to make sure developers had enough information to troubleshoot failures quickly, including Jenkins build URL, failed stage, test reports, scan reports, image URI, Git SHA, and artifact location.

### Real-time issues

One common issue was email notification not being sent after pipeline completion.

I checked Jenkins Email Extension plugin configuration, SMTP server details, sender address, recipient list, credentials, and whether the post block was correctly configured under success or failure condition.

Another issue was report links missing or broken. I checked whether reports were archived in Jenkins, uploaded to S3/artifact storage, and whether paths were correctly included in the email body.

### Interview summary

Report and Notify stage gives visibility into the CI result and helps the team quickly troubleshoot failures or confirm successful builds.

---

## Post-Build Action — Cleanup If Required

### Is cleanup a main CI stage?

Cleanup is useful, but it should not be presented as a separate main CI stage.

It is better explained as a Jenkins post-build action.

The main CI flow ends with Report / Notify.

Cleanup happens after important reports and artifacts are archived.

### When cleanup is important

If Jenkins uses static agents like EC2 instances or long-running VMs, cleanup is important because old workspaces, temporary files, Docker images, dangling layers, Maven cache issues, and scan reports can slowly fill disk space.

In that case, we clean temporary files or workspace data after reports and artifacts are safely archived.

If Jenkins uses Kubernetes-based ephemeral agents, cleanup is less critical because the agent pod is destroyed after the build. Once the pod is deleted, its workspace also goes away.

Still, we may clean temporary files or sensitive scan outputs as a good practice.

### Interview summary

Cleanup was handled as a post-build action if required. For static Jenkins agents, it helped avoid disk space issues. For Kubernetes ephemeral agents, cleanup was less critical because the build pod was destroyed after completion, but temporary files could still be cleaned after reports and artifacts were archived.

---

## Final Tool Mapping

Source / Checkout             -> Git / Jenkins SCM Checkout
Environment Setup             -> Jenkins env variables + AWS STS validation
Secret Scan                   -> Gitleaks
Dependency Scan / SCA         -> OWASP Dependency-Check
Build / Compile               -> Maven
Unit Test                     -> JUnit + Maven Surefire
SAST / Code Quality           -> SonarQube + Quality Gate
Integration Test              -> JUnit + Maven Failsafe / Test Profile
Package Artifact              -> Maven package
Container Image Build         -> Kaniko or Docker Build
Container Image Scan          -> Trivy
Publish / Push                -> Amazon ECR + S3/Nexus/Artifactory
Report / Notify               -> Outlook Email via Jenkins Email Extension Plugin
Cleanup                       -> Jenkins post-build action if required

---

## Final Short Interview Answer

In our project, we had a Jenkins-based CI pipeline for Java/Spring Boot services.

The pipeline started with source checkout, where Jenkins pulled the code from Git and captured branch name and Git commit SHA. Then we had environment setup, where Jenkins validated AWS identity using aws sts get-caller-identity and prepared variables like service name, AWS region, ECR repository, image tag, and artifact paths.

After that, we ran Gitleaks for secret scanning to detect hardcoded credentials. Then we used OWASP Dependency-Check for dependency vulnerability scanning to identify known CVEs in Java/Maven libraries.

Once the early security gates passed, Maven compiled the application. Unit tests were executed using JUnit and Maven Surefire, and Jenkins published the test reports. After unit testing, SonarQube analysis and Quality Gate validation were performed to check code quality, bugs, vulnerabilities, code smells, duplication, and coverage.

Then we ran integration tests using Maven Failsafe or a test profile to validate whether components worked together correctly, such as API, service, repository, database, and external integrations.

After successful validation, Maven packaged the application into a JAR artifact. We followed the build once, deploy many principle, so the same tested artifact was used for image creation and promotion across environments.

Next, the container image was built using an optimized Dockerfile, usually with a lightweight runtime image and non-root user. The image was tagged using the Git SHA for traceability.

After the image was built, Trivy scanned the final container image for OS package and runtime vulnerabilities. If fixable high or critical vulnerabilities were found, the pipeline failed based on the security gate.

Once the image passed scanning, it was pushed to Amazon ECR. Artifacts and reports such as JAR file, test reports, dependency scan report, image scan report, SonarQube result link, and build manifest were archived in Jenkins or uploaded to S3/Nexus/Artifactory.

The final CI stage was Report and Notify, where Jenkins sent an Outlook email to the development, QA, and DevOps teams with build status, Git SHA, image URI, artifact/report links, and Jenkins build URL.

Cleanup was handled as a Jenkins post-build action if required, especially for static Jenkins agents to avoid disk space issues.
