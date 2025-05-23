# Blue-Green Deployment Infrastructure

This repository contains Terraform code for setting up a blue-green deployment infrastructure on AWS using ECS, ALB, and ECR.

## Architecture

The infrastructure consists of the following components:

- VPC with public subnets (and optional private subnets)
- Application Load Balancer (ALB) with blue and green target groups
- Amazon ECR repository for container images
- ECS cluster with blue and green services
- Security groups for controlling access

## Module Structure

```
.
├── main.tf                 # Main Terraform configuration
├── variables.tf            # Variable definitions
├── outputs.tf              # Output definitions
├── terraform.tfvars        # Variable values
├── modules/
│   ├── alb/                # ALB module
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── ecr/                # ECR module
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── ecs/                # ECS module
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── iam/                # IAM module
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   ├── security_group/     # Security Group module
│   │   ├── main.tf
│   │   ├── variables.tf
│   │   └── outputs.tf
│   └── vpc/                # VPC module
│       ├── main.tf
│       ├── variables.tf
│       └── outputs.tf
```

## Usage

1. Configure your AWS credentials:

```bash
export AWS_ACCESS_KEY_ID="your-access-key"
export AWS_SECRET_ACCESS_KEY="your-secret-key"
export AWS_REGION="us-east-1"
```

2. Update the `terraform.tfvars` file with your desired configuration.

3. Initialize Terraform:

```bash
terraform init
```

4. Plan the deployment:

```bash
terraform plan -out=tfplan
```

5. Apply the changes:

```bash
terraform apply tfplan
```

## Variable Structure

The configuration uses a hierarchical variable structure for better organization:

```hcl
aws = {
  region = "us-east-1"
  tags = {
    Environment = "dev"
    Project     = "blue-green-deployment"
    ManagedBy   = "Terraform"
  }
}

vpc = {
  cidr_block           = "10.0.0.0/16"
  name                 = "Main VPC"
  public_subnet_cidrs  = ["10.0.1.0/24", "10.0.2.0/24"]
  availability_zones   = ["us-east-1a", "us-east-1b"]
  # ... other vpc settings
}

alb = {
  name                 = "blue-green-alb"
  listener_port        = 80
  # ... other alb settings
}

# ... other resource configurations
```

## Blue-Green Deployment Process

1. Initially, all traffic is routed to the blue environment.
2. Deploy a new version to the green environment.
3. Test the green environment using direct access.
4. When ready, update the ALB listener rules to route traffic to the green environment.
5. If issues are detected, roll back by routing traffic back to the blue environment.
6. For the next deployment, the process is reversed (deploy to blue, test, switch).

## Requirements

- Terraform >= 1.0.0
- AWS CLI >= 2.0.0
- AWS account with appropriate permissions

## Notes

- The task role ARN must be provided in the terraform.tfvars file or as a variable.
- The Docker image is built and pushed to ECR automatically unless `skip_docker_build` is set to true.