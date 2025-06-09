############################################
# VPC Resources
############################################

resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = var.enable_dns_support
  enable_dns_hostnames = var.enable_dns_hostnames
  instance_tenancy     = var.instance_tenancy

  tags = merge(
    {
      Name        = var.vpc_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

############################################
# Public Subnet Resources
############################################

resource "aws_subnet" "public_subnets" {
  for_each = { for idx, cidr in var.public_subnet_cidrs : idx => cidr if idx < min(length(var.public_subnet_cidrs), length(var.availability_zones)) }

  vpc_id                  = aws_vpc.main.id
  cidr_block              = each.value
  availability_zone       = var.availability_zones[each.key]
  map_public_ip_on_launch = var.map_public_ip_on_launch

  tags = merge(
    {
      Name        = "${var.subnet_name_prefix}-${each.key + 1}"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
      Type        = var.public_subnet_type
    },
    var.additional_tags
  )
}

# Create an Internet Gateway
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = merge(
    {
      Name        = var.igw_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

# Create a public route table
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  tags = merge(
    {
      Name        = var.route_table_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
      Type        = var.public_route_table_type
    },
    var.additional_tags
  )
}

# Add route to Internet Gateway
resource "aws_route" "public_internet_gateway" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = var.internet_cidr_block
  gateway_id             = aws_internet_gateway.main.id

  timeouts {
    create = var.route_creation_timeout
  }
}

# Associate public subnets with the public route table
resource "aws_route_table_association" "public" {
  for_each = aws_subnet.public_subnets

  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

############################################
# Private Subnet Resources (Optional)
############################################

# Optional: Create private subnets if enabled
resource "aws_subnet" "private_subnets" {
  for_each = var.create_private_subnets ? { for idx, cidr in var.private_subnet_cidrs : idx => cidr if idx < min(length(var.public_subnet_cidrs), length(var.availability_zones)) } : {}

  vpc_id            = aws_vpc.main.id
  cidr_block        = each.value
  availability_zone = var.availability_zones[each.key]

  tags = merge(
    {
      Name        = "${var.private_subnet_name_prefix}-${each.key + 1}"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
      Type        = var.private_subnet_type
    },
    var.additional_tags
  )
}

# Optional: Create NAT Gateway if private subnets are enabled
resource "aws_eip" "nat" {
  count  = var.create_private_subnets && var.create_nat_gateway ? 1 : 0
  domain = "vpc"

  tags = merge(
    {
      Name        = "${var.vpc_name}-${var.nat_eip_name_suffix}"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

resource "aws_nat_gateway" "main" {
  count = var.create_private_subnets && var.create_nat_gateway ? 1 : 0

  allocation_id = aws_eip.nat[0].id
  subnet_id     = element([for s in aws_subnet.public_subnets : s.id], 0)

  tags = merge(
    {
      Name        = "${var.vpc_name}-${var.nat_gateway_name_suffix}"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )

  depends_on = [aws_internet_gateway.main]
}

# Create private route table if private subnets are enabled
resource "aws_route_table" "private" {
  count = var.create_private_subnets ? 1 : 0
  
  vpc_id = aws_vpc.main.id

  tags = merge(
    {
      Name        = "${var.vpc_name}-${var.private_route_table_name_suffix}"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
      Type        = var.private_route_table_type
    },
    var.additional_tags
  )
}

# Add route to NAT Gateway if created
resource "aws_route" "private_nat_gateway" {
  count = var.create_private_subnets && var.create_nat_gateway ? 1 : 0
  
  route_table_id         = aws_route_table.private[0].id
  destination_cidr_block = var.internet_cidr_block
  nat_gateway_id         = aws_nat_gateway.main[0].id

  timeouts {
    create = var.route_creation_timeout
  }
}

# Associate private subnets with the private route table
resource "aws_route_table_association" "private" {
  for_each = var.create_private_subnets ? aws_subnet.private_subnets : {}

  subnet_id      = each.value.id
  route_table_id = aws_route_table.private[0].id
}

############################################
# VPC Flow Logs (Optional)
############################################

# Optional: VPC Flow Logs
resource "aws_flow_log" "vpc_flow_log" {
  count = var.enable_flow_logs ? 1 : 0
  
  iam_role_arn    = var.flow_logs_iam_role_arn
  log_destination = var.flow_logs_destination
  traffic_type    = var.flow_logs_traffic_type
  vpc_id          = aws_vpc.main.id

  tags = merge(
    {
      Name        = "${var.vpc_name}-${var.flow_logs_name_suffix}"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}