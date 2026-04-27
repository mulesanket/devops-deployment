########################################
# RDS Aurora PostgreSQL Serverless v2
########################################

# --- DB Subnet Group ---
resource "aws_db_subnet_group" "aurora" {
  name       = "${var.project_name}-${var.environment}-db-subnet-group"
  subnet_ids = var.private_subnet_ids

  tags = {
    Name        = "${var.project_name}-db-subnet-group"
    Environment = var.environment
  }
}

# --- Security Group ---
resource "aws_security_group" "aurora" {
  name        = "${var.project_name}-${var.environment}-aurora-sg"
  description = "Allow PostgreSQL access from EKS worker nodes"
  vpc_id      = var.vpc_id

  ingress {
    description     = "PostgreSQL from EKS nodes"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = var.eks_node_security_group_ids
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "${var.project_name}-aurora-sg"
    Environment = var.environment
  }
}

# --- Aurora Cluster ---
resource "aws_rds_cluster" "aurora" {
  cluster_identifier = "${var.project_name}-${var.environment}-cluster"
  engine             = "aurora-postgresql"
  engine_mode        = "provisioned"
  engine_version     = var.engine_version
  database_name      = var.database_name
  master_username    = var.master_username
  master_password    = var.master_password
  port               = 5432

  db_subnet_group_name   = aws_db_subnet_group.aurora.name
  vpc_security_group_ids = [aws_security_group.aurora.id]

  storage_encrypted = true
  deletion_protection = var.deletion_protection

  skip_final_snapshot       = var.environment == "dev" || var.environment == "development" ? true : false
  final_snapshot_identifier = var.environment == "dev" || var.environment == "development" ? null : "${var.project_name}-${var.environment}-final-snapshot"

  serverlessv2_scaling_configuration {
    min_capacity = var.serverless_min_capacity
    max_capacity = var.serverless_max_capacity
  }

  tags = {
    Name        = "${var.project_name}-aurora-cluster"
    Environment = var.environment
  }
}

# --- Aurora Cluster Instance (Serverless v2) ---
resource "aws_rds_cluster_instance" "aurora" {
  count              = var.instance_count
  identifier         = "${var.project_name}-${var.environment}-instance-${count.index + 1}"
  cluster_identifier = aws_rds_cluster.aurora.id
  instance_class     = "db.serverless"
  engine             = aws_rds_cluster.aurora.engine
  engine_version     = aws_rds_cluster.aurora.engine_version

  tags = {
    Name        = "${var.project_name}-aurora-instance-${count.index + 1}"
    Environment = var.environment
  }
}
