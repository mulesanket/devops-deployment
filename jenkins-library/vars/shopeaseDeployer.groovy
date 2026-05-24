// ============================================
// Shopease Jenkins shared library - Deployer (CD) agent pod template
//
// Author: Sanket Mule
// --------------------------------------------
// Why a SEPARATE pod template from shopeaseAgent():
//   shopeaseAgent() is a fat 5-container pod (maven, kaniko, aws,
//   tools, jnlp) designed for CI: ~3.5 CPU / 4Gi memory requests.
//   A CD run only needs `aws eks update-kubeconfig` + `kubectl set
//   image` + `kubectl rollout status`. Spinning up maven + kaniko
//   for that is wasteful and slows pod scheduling.
//
//   This pod has only two non-jnlp containers (aws-cli, kubectl)
//   and tiny resource requests, so it schedules in seconds.
//
// IRSA:
//   Reuses the same `jenkins-agent-builder` ServiceAccount (same
//   IRSA role). The role's IAM policy already includes
//   `eks:DescribeCluster` (see infrastructure-terraform/.../ci-cd.tf),
//   and the role is mapped to the K8s group `shopease-deployers`
//   via an EKS Access Entry, which is then bound to a least-priv
//   Role in the `shopease-webapp-development` namespace.
//
// Usage in a service Jenkinsfile.cd:
//
//   @Library('shopease-jenkins-library') _
//
//   pipeline {
//       agent {
//           kubernetes {
//               yaml shopeaseDeployer(serviceName: 'auth-service')
//               defaultContainer 'jnlp'
//           }
//       }
//       stages {
//           stage('Deploy') {
//               steps {
//                   container('aws')     { sh 'aws eks update-kubeconfig ...' }
//                   container('kubectl') { sh 'kubectl apply -f ...'         }
//               }
//           }
//       }
//   }
// ============================================

def call(Map cfg = [:]) {
    String serviceName = cfg.serviceName ?: error('shopeaseDeployer: serviceName is required')

    String namespace      = cfg.namespace      ?: 'jenkins-cicd-agents'
    String serviceAccount = cfg.serviceAccount ?: 'jenkins-agent-builder'
    String awsImage       = cfg.awsImage       ?: 'amazon/aws-cli:2.17.18'
    // bitnami/kubectl is small (~80MB) and pinned to a K8s minor release.
    // Keep its minor version within +/-1 of the EKS control plane version.
    String kubectlImage   = cfg.kubectlImage   ?: 'bitnami/kubectl:1.30'
    String jnlpImage      = cfg.jnlpImage      ?: 'jenkins/inbound-agent:latest'

    return """
apiVersion: v1
kind: Pod
metadata:
  labels:
    app.kubernetes.io/part-of: jenkins-cd
    app.kubernetes.io/component: deployer
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
      env:
        - name: AWS_SDK_LOAD_CONFIG
          value: "true"
      volumeMounts:
        - name: workspace-volume
          mountPath: /home/jenkins/agent
        - name: kube-config
          mountPath: /root/.kube
    - name: kubectl
      image: ${kubectlImage}
      command: ["sleep"]
      args: ["infinity"]
      tty: true
      # bitnami/kubectl images run as non-root (UID 1001) by default,
      # which clashes with the root-owned shared workspace + kubeconfig
      # written by the aws container. Override to root for parity.
      securityContext:
        runAsUser: 0
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
        - name: kube-config
          mountPath: /root/.kube
  volumes:
    - name: workspace-volume
      emptyDir: {}
    # Shared between `aws` (writes kubeconfig) and `kubectl` (reads it).
    # emptyDir is fine: pod lives ~2 min, then dies with the build.
    - name: kube-config
      emptyDir: {}
"""
}
