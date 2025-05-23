variable "ecs_cluster_name" {
  description = "Name of the ECS cluster"
  type        = string
}

variable "task_family" {
  description = "Family name for the task definition"
  type        = string
}

variable "task_role_arn" {
  description = "ARN of the IAM role for the task"
  type        = string
  default     = null
}

variable "cpu" {
  description = "CPU units for the task"
  type        = string
}

variable "memory" {
  description = "Memory for the task in MB"
  type        = string
}

variable "container_name" {
  description = "Name of the container"
  type        = string
}

variable "container_image" {
  description = "Docker image for the container"
  type        = string
}

variable "container_port" {
  description = "Port exposed by the container"
  type        = number
}

variable "desired_count" {
  description = "Desired number of tasks"
  type        = number
}

variable "public_subnet_ids" {
  description = "List of public subnet IDs"
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group ID to associate with ECS tasks"
  type        = string
}

variable "blue_target_group_arn" {
  description = "ARN of the Blue target group"
  type        = string
}

variable "green_target_group_arn" {
  description = "ARN of the Green target group"
  type        = string
}

variable "ecs_task_definition" {
  description = "The ECS task definition for Fargate"
  type        = string
}

variable "execution_role_name" {
  description = "Name of the ECS task execution role"
  type        = string
  default     = "ecs-task-execution-role"
}

variable "network_mode" {
  description = "Network mode for the task definition"
  type        = string
  default     = "awsvpc"
}

variable "requires_compatibilities" {
  description = "List of launch types required by the task"
  type        = list(string)
  default     = ["FARGATE"]
}

variable "blue_service_name" {
  description = "Name of the blue service"
  type        = string
  default     = "blue-service"
}

variable "green_service_name" {
  description = "Name of the green service"
  type        = string
  default     = "green-service"
}

variable "launch_type" {
  description = "Launch type for the ECS service"
  type        = string
  default     = "FARGATE"
}

variable "assign_public_ip" {
  description = "Whether to assign a public IP to the task"
  type        = bool
  default     = true
}

variable "green_desired_count" {
  description = "Initial desired count for the green service"
  type        = number
  default     = 0
}

variable "additional_tags" {
  description = "Additional tags for ECS resources"
  type        = map(string)
  default     = {}
}

variable "environment" {
  description = "Environment name (e.g., dev, staging, prod)"
  type        = string
  default     = "dev"
}

variable "enable_container_insights" {
  description = "Whether to enable CloudWatch Container Insights for the cluster"
  type        = bool
  default     = false
}

variable "enable_container_logs" {
  description = "Whether to enable CloudWatch logs for containers"
  type        = bool
  default     = false
}

variable "log_group_name" {
  description = "Name of the CloudWatch log group for container logs"
  type        = string
  default     = "/ecs/blue-green-app"
}

variable "log_retention_days" {
  description = "Number of days to retain container logs"
  type        = number
  default     = 30
}

variable "aws_region" {
  description = "AWS region for resources"
  type        = string
  default     = "us-east-1"
}

variable "container_environment" {
  description = "Environment variables for the container"
  type = list(object({
    name  = string
    value = string
  }))
  default = []
}

variable "container_secrets" {
  description = "Secrets for the container from AWS Secrets Manager or Parameter Store"
  type = list(object({
    name      = string
    valueFrom = string
  }))
  default = []
}

variable "task_volumes" {
  description = "Task volumes configuration"
  type = list(object({
    name = string
    efs_volume_configuration = optional(object({
      file_system_id          = string
      root_directory          = optional(string)
      transit_encryption      = optional(string)
      transit_encryption_port = optional(number)
    }))
  }))
  default = []
}

variable "deployment_maximum_percent" {
  description = "Maximum percentage of tasks that can be running during a deployment"
  type        = number
  default     = 200
}

variable "deployment_minimum_healthy_percent" {
  description = "Minimum percentage of tasks that must remain healthy during a deployment"
  type        = number
  default     = 100
}

variable "health_check_grace_period_seconds" {
  description = "Seconds to ignore failing load balancer health checks on newly provisioned tasks"
  type        = number
  default     = 60
}

variable "enable_fargate_capacity_providers" {
  description = "Whether to enable Fargate capacity providers"
  type        = bool
  default     = false
}

variable "capacity_provider_strategy" {
  description = "Capacity provider strategy for the service"
  type = list(object({
    capacity_provider = string
    weight            = number
    base              = optional(number)
  }))
  default = [
    {
      capacity_provider = "FARGATE"
      weight            = 1
    }
  ]
}

variable "execute_command_logging" {
  description = "The log setting to use for redirecting logs for task execute-command results"
  type        = string
  default     = "DEFAULT"
}

variable "module_name" {
  description = "Name of the module for tagging"
  type        = string
  default     = "ecs"
}

variable "terraform_managed" {
  description = "Indicates if the resource is managed by Terraform"
  type        = string
  default     = "true"
}

variable "blue_deployment_type" {
  description = "Deployment type for blue resources"
  type        = string
  default     = "blue"
}

variable "green_deployment_type" {
  description = "Deployment type for green resources"
  type        = string
  default     = "green"
}

variable "blue_log_stream_prefix" {
  description = "Log stream prefix for blue container"
  type        = string
  default     = "blue"
}

variable "green_log_stream_prefix" {
  description = "Log stream prefix for green container"
  type        = string
  default     = "green"
}

variable "container_protocol" {
  description = "Protocol for container port mappings"
  type        = string
  default     = "tcp"
}

variable "logs_policy_name" {
  description = "Name for the CloudWatch logs IAM policy"
  type        = string
  default     = "ecs-logs-policy"
}

variable "blue_service_tag_name" {
  description = "Name tag for blue service"
  type        = string
  default     = "Blue Service"
}

variable "green_service_tag_name" {
  description = "Name tag for green service"
  type        = string
  default     = "Green Service"
}

variable "iam_policy_version" {
  description = "IAM policy document version"
  type        = string
  default     = "2012-10-17"
}

variable "iam_service_principal" {
  description = "IAM service principal for ECS tasks"
  type        = string
  default     = "ecs-tasks.amazonaws.com"
}

variable "container_essential" {
  description = "Whether the container is essential"
  type        = bool
  default     = true
}

variable "log_driver" {
  description = "Log driver for container logs"
  type        = string
  default     = "awslogs"
}

variable "task_execution_policy_arn" {
  description = "ARN of the task execution policy to attach to the role"
  type        = string
  default     = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

variable "green_container_name_suffix" {
  description = "Suffix to append to container name for green deployment"
  type        = string
  default     = "-green"
}

variable "green_task_family_suffix" {
  description = "Suffix to append to task family for green deployment"
  type        = string
  default     = "-green"
}

variable "efs_root_directory_default" {
  description = "Default root directory for EFS volume configuration"
  type        = string
  default     = "/"
}

variable "efs_transit_encryption_default" {
  description = "Default transit encryption for EFS volume configuration"
  type        = string
  default     = "ENABLED"
}

variable "blue_task_name_suffix" {
  description = "Suffix to append to task family name for blue task tag"
  type        = string
  default     = "-blue"
}

variable "green_task_name_suffix" {
  description = "Suffix to append to task family name for green task tag"
  type        = string
  default     = "-green"
}

variable "log_resource_suffix" {
  description = "Suffix for CloudWatch log resource ARN"
  type        = string
  default     = ":*"
}
