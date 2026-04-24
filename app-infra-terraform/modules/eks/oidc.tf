
data "aws_eks_cluster" "eks_control_plane" {
  name = aws_eks_cluster.eks_control_plane.name
}

data "aws_eks_cluster_auth" "eks_control_plane" {
  name = aws_eks_cluster.eks_control_plane.name
}

resource "aws_iam_openid_connect_provider" "eks_control_plane" {
  url            = data.aws_eks_cluster.eks_control_plane.identity[0].oidc[0].issuer
  client_id_list = ["sts.amazonaws.com"]

  tags = {
    Name = "${var.eks_cluster_name}-oidc"
  }
}

output "oidc_provider_arn" {
  value = aws_iam_openid_connect_provider.eks_control_plane.arn
}

output "oidc_provider_url" {
  value = aws_iam_openid_connect_provider.eks_control_plane.url
}