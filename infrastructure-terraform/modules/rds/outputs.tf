output "cluster_endpoint" {
  description = "Writer endpoint of the Aurora cluster"
  value       = aws_rds_cluster.aurora.endpoint
}

output "cluster_reader_endpoint" {
  description = "Reader endpoint of the Aurora cluster"
  value       = aws_rds_cluster.aurora.reader_endpoint
}

output "cluster_port" {
  description = "Port of the Aurora cluster"
  value       = aws_rds_cluster.aurora.port
}

output "database_name" {
  description = "Name of the default database"
  value       = aws_rds_cluster.aurora.database_name
}

output "security_group_id" {
  description = "Security group ID of the Aurora cluster"
  value       = aws_security_group.aurora.id
}
