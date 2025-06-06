output "vpc_id" {
  description = "The ID of the VPC"
  value       = aws_vpc.main.id
}

output "vpc_arn" {
  description = "The ARN of the VPC"
  value       = aws_vpc.main.arn
}

output "vpc_cidr_block" {
  description = "The CIDR block of the VPC"
  value       = aws_vpc.main.cidr_block
}

output "public_subnet_ids" {
  description = "List of IDs of public subnets"
  value       = [for subnet in aws_subnet.public_subnets : subnet.id]
}

output "public_subnet_arns" {
  description = "List of ARNs of public subnets"
  value       = [for subnet in aws_subnet.public_subnets : subnet.arn]
}

output "public_subnet_cidrs" {
  description = "List of CIDR blocks of public subnets"
  value       = [for subnet in aws_subnet.public_subnets : subnet.cidr_block]
}

output "private_subnet_ids" {
  description = "List of IDs of private subnets"
  value       = var.create_private_subnets ? [for subnet in aws_subnet.private_subnets : subnet.id] : []
}

output "private_subnet_arns" {
  description = "List of ARNs of private subnets"
  value       = var.create_private_subnets ? [for subnet in aws_subnet.private_subnets : subnet.arn] : []
}

output "private_subnet_cidrs" {
  description = "List of CIDR blocks of private subnets"
  value       = var.create_private_subnets ? [for subnet in aws_subnet.private_subnets : subnet.cidr_block] : []
}

output "internet_gateway_id" {
  description = "The ID of the Internet Gateway"
  value       = aws_internet_gateway.main.id
}

output "public_route_table_id" {
  description = "The ID of the public route table"
  value       = aws_route_table.public.id
}

output "private_route_table_id" {
  description = "The ID of the private route table"
  value       = var.create_private_subnets ? aws_route_table.private[0].id : null
}

output "nat_gateway_id" {
  description = "The ID of the NAT Gateway"
  value       = var.create_private_subnets && var.create_nat_gateway ? aws_nat_gateway.main[0].id : null
}

output "nat_gateway_public_ip" {
  description = "The public IP address of the NAT Gateway"
  value       = var.create_private_subnets && var.create_nat_gateway ? aws_eip.nat[0].public_ip : null
}

output "vpc_flow_log_id" {
  description = "The ID of the VPC Flow Log"
  value       = var.enable_flow_logs ? aws_flow_log.vpc_flow_log[0].id : null
}