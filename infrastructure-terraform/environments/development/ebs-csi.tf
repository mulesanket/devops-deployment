# ============================================
# Shopease: EBS CSI Driver (EKS add-on)
#
# Author: Sanket Mule
# --------------------------------------------
# Purpose:
#   The EBS CSI driver lets Kubernetes dynamically provision EBS volumes
#   for PersistentVolumeClaims. Without this driver, PVCs stay in Pending
#   state forever because nothing inside the cluster knows how to call
#   the AWS EBS API.
#
# Resources created here:
#   1. IRSA role for the driver's controller pod
#      (SA "ebs-csi-controller-sa" in namespace "kube-system" - names are
#       fixed by AWS, do not change).
#   2. Attaches AWS-managed policy "AmazonEBSCSIDriverPolicy".
#   3. EKS add-on "aws-ebs-csi-driver" - EKS installs/upgrades the driver
#      pods for us.
#
# Used by:
#   - Jenkins agent pods (Maven .m2 cache, Trivy vuln DB cache)
#   - Any future workload that needs gp3 PersistentVolumes
# ============================================

# ---------------------------------------------------------------------------
# 1. IRSA role for the EBS CSI driver controller
# ---------------------------------------------------------------------------
module "ebs_csi_irsa" {
  source = "../../modules/irsa"

  role_name            = "${var.project_name}-${var.environment}-ebs-csi-irsa"
  oidc_provider_arn    = module.eks.oidc_provider_arn
  oidc_provider_url    = module.eks.oidc_provider_url
  namespace            = "kube-system"
  service_account_name = "ebs-csi-controller-sa"

  # AWS-managed policy with the minimum perms the driver needs:
  # ec2:CreateVolume, AttachVolume, DetachVolume, DescribeVolumes, etc.
  policy_arns = ["arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"]

  tags = {
    Component   = "ebs-csi-driver"
    Environment = var.environment
  }

  depends_on = [module.eks]
}

# ---------------------------------------------------------------------------
# 2. EKS add-on: aws-ebs-csi-driver
#    EKS will pull the driver image, install the controller Deployment +
#    node DaemonSet, create the SA, and keep it upgraded.
# ---------------------------------------------------------------------------
resource "aws_eks_addon" "ebs_csi" {
  cluster_name                = module.eks.eks_cluster_name
  addon_name                  = "aws-ebs-csi-driver"
  service_account_role_arn    = module.ebs_csi_irsa.role_arn
  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = {
    Component   = "ebs-csi-driver"
    Environment = var.environment
    ManagedBy   = "terraform"
  }

  depends_on = [module.ebs_csi_irsa]
}
