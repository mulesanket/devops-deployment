
output "vpc_id" {
  value = aws_vpc.main.id
}

output "public_subnet_ids" {
  value = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  value = aws_subnet.private[*].id
}

output "igw_id" {
  value = aws_internet_gateway.igw.id
}

output "nat_gateway_id_1a" {
  value = aws_nat_gateway.nat_1a.id
}

output "nat_gateway_id_1b" {
  value = aws_nat_gateway.nat_1b.id
}

output "nat_gateway_id_1c" {
  value = aws_nat_gateway.nat_1c.id
}
