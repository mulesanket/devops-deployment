// ============================================
// Shopease Jenkins shared library — Build agent pod template
//
// Author: Sanket Mule
// --------------------------------------------
// Usage in a service Jenkinsfile:
//
//   @Library('shopease-jenkins-library') _
//
//   pipeline {
//       agent {
//           kubernetes {
//               yaml shopeaseAgent(serviceName: 'auth-service')
//               defaultContainer 'jnlp'
//           }
//       }
//       stages {
//           stage('Build')        { steps { container('maven')  { sh 'mvn verify' } } }
//           stage('Scan deps')    { steps { container('tools')  { sh 'trivy fs .' } } }
//           stage('Image build')  { steps { container('kaniko') { sh '/kaniko/executor ...' } } }
//           stage('S3 upload')    { steps { container('aws')    { sh 'aws s3 cp ...' } } }
//       }
//   }
//
// Containers in the pod:
//   jnlp   — Jenkins agent process (always default)
//   maven  — mvn build/test/package
//   kaniko — daemonless Docker image builder + pusher
//   aws    — aws CLI v2 for ECR token, S3, describe-images
//   tools  — gitleaks + trivy + jq + git in one small image
//
// All containers share the workspace volume at
// /home/jenkins/agent. Maven (.m2) and Trivy DB caches are repopulated
// per build from S3 tarballs (see Jenkinsfile "Restore Caches" stage and
// docs/jenkins/04-caching-strategy.md for the design rationale).
// ============================================

def call(Map cfg = [:]) {
    // Required: serviceName (e.g., 'auth-service')
    String serviceName = cfg.serviceName ?: error('shopeaseAgent: serviceName is required')

    // Defaults — override per-service if needed
    String namespace        = cfg.namespace        ?: 'jenkins-cicd-agents'
    String serviceAccount   = cfg.serviceAccount   ?: 'jenkins-agent-builder'
    String mavenImage       = cfg.mavenImage       ?: 'maven:3.9-eclipse-temurin-21'
    String kanikoImage      = cfg.kanikoImage      ?: 'gcr.io/kaniko-project/executor:v1.23.2-debug'
    String awsImage         = cfg.awsImage         ?: 'amazon/aws-cli:2.17.18'    String toolsImage       = cfg.toolsImage       ?: 'aquasec/trivy:0.55.0'   // has trivy + jq baked in; we add gitleaks via initContainer
    String jnlpImage        = cfg.jnlpImage        ?: 'jenkins/inbound-agent:latest'

    return """
apiVersion: v1
kind: Pod
metadata:
  labels:
    app.kubernetes.io/part-of: jenkins-ci
    app.kubernetes.io/component: build-agent
    shopease.io/service: ${serviceName}
spec:
  serviceAccountName: ${serviceAccount}
  restartPolicy: Never
  # Build pods are best-effort; let cluster reclaim if pressure.
  terminationGracePeriodSeconds: 10
  securityContext:
    runAsUser: 0
    fsGroup: 0
  containers:    # NOTE: requests are intentionally small (the scheduler only
    # reserves the request amount). Limits stay generous so heavy
    # stages (mvn, kaniko) can burst when nodes have spare capacity.
    # ---- 1. JNLP — Jenkins agent process ----
    - name: jnlp
      image: ${jnlpImage}
      resources:
        requests: { cpu: "50m",  memory: "128Mi" }
        limits:   { cpu: "500m", memory: "512Mi" }
      volumeMounts:
        - { name: workspace-volume, mountPath: /home/jenkins/agent }

    # ---- 2. MAVEN — mvn build/test/package ----
    - name: maven
      image: ${mavenImage}
      command: ["sleep"]
      args: ["infinity"]
      tty: true
      resources:
        requests: { cpu: "100m", memory: "512Mi" }
        limits:   { cpu: "2",    memory: "2Gi" }
      volumeMounts:
        - { name: workspace-volume, mountPath: /home/jenkins/agent }
        - { name: maven-cache,      mountPath: /root/.m2 }

    # ---- 3. KANIKO — build & push images (no docker daemon) ----
    - name: kaniko
      image: ${kanikoImage}
      command: ["sleep"]
      args: ["infinity"]
      tty: true
      resources:
        requests: { cpu: "100m", memory: "512Mi" }
        limits:   { cpu: "2",    memory: "2Gi" }
      env:
        - name: AWS_SDK_LOAD_CONFIG
          value: "true"
      volumeMounts:
        - { name: workspace-volume, mountPath: /home/jenkins/agent }
        - { name: kaniko-cache,     mountPath: /kaniko/.cache }

    # ---- 4. AWS CLI — ECR token, S3 upload, describe-images ----
    - name: aws
      image: ${awsImage}
      command: ["sleep"]
      args: ["infinity"]
      tty: true
      entrypoint: [""]
      resources:
        requests: { cpu: "50m",  memory: "128Mi" }
        limits:   { cpu: "500m", memory: "512Mi" }
      volumeMounts:
        - { name: workspace-volume, mountPath: /home/jenkins/agent }

    # ---- 5. TOOLS — trivy + jq + git (gitleaks added separately if needed) ----
    - name: tools
      image: ${toolsImage}
      command: ["sleep"]
      args: ["infinity"]
      tty: true
      resources:
        requests: { cpu: "50m",  memory: "256Mi" }
        limits:   { cpu: "1",    memory: "1Gi" }
      volumeMounts:
        - { name: workspace-volume, mountPath: /home/jenkins/agent }  volumes:
    - name: workspace-volume
      emptyDir: {}
    # NOTE: All cache volumes are emptyDir (per-pod, ephemeral).
    # Cross-build cache persistence is handled at the application level
    # via S3 tarball cache in the Jenkinsfile (see "Restore Caches" and
    # "Save Caches" stages). Reasoning:
    #   - S3 is multi-AZ; an EBS PVC would pin builds to one AZ.
    #   - S3 supports unlimited parallel readers; EBS RWO blocks parallel
    #     builds on the same volume.
    #   - No PVC orphan cleanup, no CSI driver dependency in the hot path.
    # See docs/jenkins/04-caching-strategy.md for the full decision record.
    - name: maven-cache
      emptyDir: {}
    - name: kaniko-cache
      emptyDir: {}
"""
}

