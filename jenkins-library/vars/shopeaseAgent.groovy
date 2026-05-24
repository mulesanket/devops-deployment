// ============================================
// Shopease Jenkins shared library - Build agent pod template
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
//           stage('Build')       { steps { container('maven')  { sh 'mvn verify' } } }
//           stage('Scan deps')   { steps { container('tools')  { sh 'trivy fs .' } } }
//           stage('Image build') { steps { container('kaniko') { sh '/kaniko/executor ...' } } }
//           stage('S3 upload')   { steps { container('aws')    { sh 'aws s3 cp ...' } } }
//       }
//   }
//
// Containers in the pod:
//   jnlp   - Jenkins agent process (always default)
//   maven  - mvn build/test/package
//   kaniko - daemonless Docker image builder + pusher
//   aws    - aws CLI v2 for ECR token, S3, describe-images
//   tools  - trivy + jq + git in one small image
//
// All containers share the workspace volume at /home/jenkins/agent.
// Maven (.m2) and Trivy DB caches are repopulated per build from S3
// tarballs (see Jenkinsfile "Restore Caches" stage and
// docs/jenkins/04-caching-strategy.md for the design rationale).
// ============================================

def call(Map cfg = [:]) {
    String serviceName = cfg.serviceName ?: error('shopeaseAgent: serviceName is required')

    String namespace      = cfg.namespace      ?: 'jenkins-cicd-agents'
    String serviceAccount = cfg.serviceAccount ?: 'jenkins-agent-builder'
    String mavenImage     = cfg.mavenImage     ?: 'maven:3.9-eclipse-temurin-21'
    String kanikoImage    = cfg.kanikoImage    ?: 'gcr.io/kaniko-project/executor:v1.23.2-debug'
    String awsImage       = cfg.awsImage       ?: 'amazon/aws-cli:2.17.18'
    String toolsImage     = cfg.toolsImage     ?: 'aquasec/trivy:0.55.0'
    String jnlpImage      = cfg.jnlpImage      ?: 'jenkins/inbound-agent:latest'

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
  terminationGracePeriodSeconds: 10
  securityContext:
    runAsUser: 0
    fsGroup: 0
  containers:
    - name: jnlp
      image: ${jnlpImage}
      resources:
        requests:
          cpu: "50m"
          memory: "128Mi"
        limits:
          cpu: "500m"
          memory: "512Mi"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
    - name: maven
      image: ${mavenImage}
      command: ["sleep"]
      args: ["infinity"]
      tty: true
      resources:
        requests:
          cpu: "100m"
          memory: "512Mi"
        limits:
          cpu: "2"
          memory: "2Gi"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: maven-cache
          mountPath: /root/.m2
    - name: kaniko
      image: ${kanikoImage}
      command: ["sleep"]
      args: ["infinity"]
      tty: true
      resources:
        requests:
          cpu: "100m"
          memory: "512Mi"
        limits:
          cpu: "2"
          memory: "2Gi"
      env:
        - name: AWS_SDK_LOAD_CONFIG
          value: "true"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: kaniko-cache
          mountPath: /kaniko/.cache
    - name: aws
      image: ${awsImage}
      command: ["sleep"]
      args: ["infinity"]
      tty: true
      resources:
        requests:
          cpu: "50m"
          memory: "128Mi"
        limits:
          cpu: "500m"
          memory: "512Mi"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
    - name: tools
      image: ${toolsImage}
      command: ["sleep"]
      args: ["infinity"]
      tty: true
      resources:
        requests:
          cpu: "50m"
          memory: "256Mi"
        limits:
          cpu: "1"
          memory: "1Gi"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
  volumes:
    - name: workspace-volume
      emptyDir: {}
    - name: maven-cache
      emptyDir: {}
    - name: kaniko-cache
      emptyDir: {}
"""
}

