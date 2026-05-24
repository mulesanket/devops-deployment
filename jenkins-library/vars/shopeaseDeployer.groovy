// ============================================
// Shopease Jenkins shared library - Deployer (CD) agent pod template
//
// Author: Sanket Mule
// --------------------------------------------
// Why a SEPARATE pod template from shopeaseAgent():
//   shopeaseAgent() is a fat 5-container pod (maven, kaniko, aws,
//   tools, jnlp) designed for CI. CD only needs aws + kubectl, so
//   we ship a tiny 3-container pod (jnlp + aws + kubectl) that
//   schedules in seconds.
//
// IRSA:
//   Reuses the `jenkins-agent-builder` ServiceAccount. The role has
//   eks:DescribeCluster and is mapped via EKS Access Entry to the
//   K8s group `shopease-deployers`, bound to a namespaced Role in
//   shopease-webapp-development.
// ============================================

def call(Map cfg = [:]) {
    String serviceName = cfg.serviceName ?: error('shopeaseDeployer: serviceName is required')

    String serviceAccount = cfg.serviceAccount ?: 'jenkins-agent-builder'
    String awsImage       = cfg.awsImage       ?: 'amazon/aws-cli:2.17.18'
    // alpine/k8s ships kubectl + aws-cli + helm on Alpine, runs as
    // root, has /bin/sh AND `sleep` (which Jenkins needs to keep the
    // container alive between `kubectl exec`-driven `sh` steps).
    // Avoids: bitnami/kubectl (tags yanked 2025) and rancher/kubectl
    // (distroless - no shell, no sleep -> StartError).
    String kubectlImage   = cfg.kubectlImage   ?: 'alpine/k8s:1.30.7'
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
    - name: kube-config
      emptyDir: {}
"""
}