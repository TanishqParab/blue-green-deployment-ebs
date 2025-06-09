############################################
# Global Configuration Variables
############################################

variable "aws" {
  description = "AWS configuration settings"
  type = object({
    region = string
    tags   = map(string)
  })
  default = {
    region = "us-east-1"
    tags = {
      Environment = "dev"
      Project     = "blue-green-deployment"
      ManagedBy   = "Terraform"
    }
  }
}

variable "additional_tags" {
  description = "Additional tags for all resources"
  type        = map(string)
  default = {
    ManagedBy = "Terraform"
  }
}

############################################
# VPC Configuration Variables
############################################

variable "vpc" {
  description = "VPC configuration settings"
  type = object({
    cidr_block                      = string
    name                            = string
    public_subnet_cidrs             = list(string)
    availability_zones              = list(string)
    enable_dns_support              = bool
    enable_dns_hostnames            = bool
    create_private_subnets          = bool
    private_subnet_cidrs            = list(string)
    create_nat_gateway              = bool
    instance_tenancy                = string
    subnet_name_prefix              = string
    igw_name                        = string
    route_table_name                = string
    map_public_ip_on_launch         = bool
    enable_flow_logs                = bool
    flow_logs_traffic_type          = string
    module_name                     = optional(string, "vpc")
    terraform_managed               = optional(string, "true")
    public_subnet_type              = optional(string, "Public")
    private_subnet_type             = optional(string, "Private")
    public_route_table_type         = optional(string, "Public")
    private_route_table_type        = optional(string, "Private")
    private_subnet_name_prefix      = optional(string, "Private-Subnet")
    nat_eip_name_suffix             = optional(string, "nat-eip")
    nat_gateway_name_suffix         = optional(string, "nat-gateway")
    private_route_table_name_suffix = optional(string, "private-rt")
    flow_logs_name_suffix           = optional(string, "flow-logs")
    internet_cidr_block             = optional(string, "0.0.0.0/0")
    route_creation_timeout          = optional(string, "5m")
    flow_logs_destination           = optional(string, "")
    flow_logs_iam_role_arn          = optional(string, "")
    az_count_calculation_method     = optional(string, "min")
  })
  default = {
    cidr_block                      = "10.0.0.0/16"
    name                            = "Main VPC"
    public_subnet_cidrs             = ["10.0.1.0/24", "10.0.2.0/24"]
    availability_zones              = ["us-east-1a", "us-east-1b"]
    enable_dns_support              = true
    enable_dns_hostnames            = true
    create_private_subnets          = false
    private_subnet_cidrs            = []
    create_nat_gateway              = false
    instance_tenancy                = "default"
    subnet_name_prefix              = "Public-Subnet"
    igw_name                        = "Main-IGW"
    route_table_name                = "Public-RT"
    map_public_ip_on_launch         = true
    enable_flow_logs                = false
    flow_logs_traffic_type          = "ALL"
    module_name                     = "vpc"
    terraform_managed               = "true"
    public_subnet_type              = "Public"
    private_subnet_type             = "Private"
    public_route_table_type         = "Public"
    private_route_table_type        = "Private"
    private_subnet_name_prefix      = "Private-Subnet"
    nat_eip_name_suffix             = "nat-eip"
    nat_gateway_name_suffix         = "nat-gateway"
    private_route_table_name_suffix = "private-rt"
    flow_logs_name_suffix           = "flow-logs"
    internet_cidr_block             = "0.0.0.0/0"
    route_creation_timeout          = "5m"
    flow_logs_destination           = ""
    flow_logs_iam_role_arn          = ""
    az_count_calculation_method     = "min"
  }
}

############################################
# ALB Configuration Variables
############################################

variable "alb" {
  description = "ALB configuration settings"
  type = object({
    name                             = string
    listener_port                    = number
    listener_protocol                = string
    health_check_path                = string
    health_check_interval            = number
    health_check_timeout             = number
    healthy_threshold                = number
    unhealthy_threshold              = number
    target_group_port                = number
    create_https_listener            = bool
    certificate_arn                  = string
    internal                         = optional(bool, false)
    load_balancer_type               = optional(string, "application")
    blue_target_group_name           = optional(string, "blue-tg")
    green_target_group_name          = optional(string, "green-tg")
    target_group_protocol            = optional(string, "HTTP")
    target_type                      = optional(string, "ip")
    health_check_matcher             = optional(string, "200")
    health_check_port                = optional(string, "traffic-port")
    health_check_protocol            = optional(string, "HTTP")
    stickiness_enabled               = optional(bool, true)
    stickiness_duration              = optional(number, 300)
    blue_weight                      = optional(number, 100)
    green_weight                     = optional(number, 0)
    enable_access_logs               = optional(bool, false)
    access_logs_bucket               = optional(string, "")
    access_logs_prefix               = optional(string, "alb-logs")
    idle_timeout                     = optional(number, 60)
    enable_deletion_protection       = optional(bool, false)
    drop_invalid_header_fields       = optional(bool, false)
    deregistration_delay             = optional(number, 300)
    target_group_stickiness_enabled  = optional(bool, false)
    target_group_stickiness_type     = optional(string, "lb_cookie")
    target_group_stickiness_duration = optional(number, 86400)
    https_port                       = optional(number, 443)
    ssl_policy                       = optional(string, "ELBSecurityPolicy-2016-08")
    module_name                      = optional(string, "alb")
    terraform_managed                = optional(string, "true")
    blue_deployment_group            = optional(string, "blue")
    green_deployment_group           = optional(string, "green")
    https_protocol                   = optional(string, "HTTPS")
    access_logs_enabled              = optional(bool, true)
    forward_action_type              = optional(string, "forward")

    # New fields for multi-app support
    application_target_groups = optional(map(object({
      blue_target_group_name  = string
      green_target_group_name = string
      target_group_port       = number
    })), {})

    application_paths = optional(map(object({
      priority     = number
      path_pattern = string
    })), {})
  })
  default = {
    name                             = "blue-green-alb"
    listener_port                    = 80
    listener_protocol                = "HTTP"
    health_check_path                = "/health"
    health_check_interval            = 30
    health_check_timeout             = 10
    healthy_threshold                = 3
    unhealthy_threshold              = 2
    target_group_port                = 5000
    create_https_listener            = false
    certificate_arn                  = ""
    internal                         = false
    load_balancer_type               = "application"
    blue_target_group_name           = "blue-tg"
    green_target_group_name          = "green-tg"
    target_group_protocol            = "HTTP"
    target_type                      = "ip"
    health_check_matcher             = "200"
    health_check_port                = "traffic-port"
    health_check_protocol            = "HTTP"
    stickiness_enabled               = true
    stickiness_duration              = 300
    blue_weight                      = 100
    green_weight                     = 0
    enable_access_logs               = false
    access_logs_bucket               = ""
    access_logs_prefix               = "alb-logs"
    idle_timeout                     = 60
    enable_deletion_protection       = false
    drop_invalid_header_fields       = false
    deregistration_delay             = 300
    target_group_stickiness_enabled  = false
    target_group_stickiness_type     = "lb_cookie"
    target_group_stickiness_duration = 86400
    https_port                       = 443
    ssl_policy                       = "ELBSecurityPolicy-2016-08"
    module_name                      = "alb"
    terraform_managed                = "true"
    blue_deployment_group            = "blue"
    green_deployment_group           = "green"
    https_protocol                   = "HTTPS"
    access_logs_enabled              = true
    forward_action_type              = "forward"
    application_target_groups        = {}
    application_paths                = {}
  }
}


############################################
# Security Group Configuration Variables
############################################

variable "security_group" {
  description = "Security group configuration settings"
  type = object({
    name        = string
    description = string
    ingress_rules = list(object({
      from_port   = number
      to_port     = number
      protocol    = string
      cidr_blocks = list(string)
      description = optional(string)
    }))
    egress_from_port   = optional(number, 0)
    egress_to_port     = optional(number, 0)
    egress_protocol    = optional(string, "-1")
    egress_cidr_blocks = optional(list(string), ["0.0.0.0/0"])
    egress_description = optional(string, "Allow all outbound traffic")
    module_name        = optional(string, "security_group")
    terraform_managed  = optional(string, "true")
  })
  default = {
    name        = "ECS Security Group"
    description = "Security group for ECS tasks"
    ingress_rules = [
      {
        from_port   = 22
        to_port     = 22
        protocol    = "tcp"
        cidr_blocks = ["0.0.0.0/0"]
        description = "SSH access"
      },
      {
        from_port   = 5000
        to_port     = 5000
        protocol    = "tcp"
        cidr_blocks = ["0.0.0.0/0"]
        description = "Flask application port"
      },
      {
        from_port   = 80
        to_port     = 80
        protocol    = "tcp"
        cidr_blocks = ["0.0.0.0/0"]
        description = "HTTP traffic"
      }
    ]
    egress_from_port   = 0
    egress_to_port     = 0
    egress_protocol    = "-1"
    egress_cidr_blocks = ["0.0.0.0/0"]
    egress_description = "Allow all outbound traffic"
    module_name        = "security_group"
    terraform_managed  = "true"
  }
}

############################################
# ECR Configuration Variables
############################################

variable "ecr" {
  description = "ECR configuration settings"
  type = object({
    repository_name        = string
    image_name             = string
    skip_docker_build      = bool
    image_tag_mutability   = string
    scan_on_push           = bool
    module_name            = optional(string, "ecr")
    terraform_managed      = optional(string, "true")
    image_tag              = optional(string, "latest")
    docker_username        = optional(string, "AWS")
    docker_build_args      = optional(string, "")
    max_retries            = optional(number, 10)
    retry_sleep_seconds    = optional(number, 5)
    file_not_found_message = optional(string, "file-not-found")
    always_run_trigger     = optional(string, "timestamp()")
    application = optional(map(object({
      repository_name = string
      image_name      = string
      image_tag       = string
    })), {})
  })
  default = {
    repository_name        = "blue-green-app"
    image_name             = "blue-green-app"
    skip_docker_build      = false
    image_tag_mutability   = "MUTABLE"
    scan_on_push           = true
    module_name            = "ecr"
    terraform_managed      = "true"
    image_tag              = "latest"
    docker_username        = "AWS"
    docker_build_args      = ""
    max_retries            = 10
    retry_sleep_seconds    = 5
    file_not_found_message = "file-not-found"
    always_run_trigger     = "timestamp()"
    application            = {}
  }
}

############################################
# ECS Configuration Variables
############################################

variable "ecs" {
  description = "ECS configuration settings"
  type = object({
    cluster_name              = string
    task_family               = string
    task_role_arn             = string
    cpu                       = string
    memory                    = string
    container_name            = string
    container_port            = number
    desired_count             = number
    execution_role_name       = string
    blue_service_name         = string
    green_service_name        = string
    task_definition           = string
    enable_container_insights = bool
    enable_container_logs     = bool
    log_group_name            = string
    log_retention_days        = number
    container_environment = list(object({
      name  = string
      value = string
    }))
    deployment_maximum_percent         = number
    deployment_minimum_healthy_percent = number
    health_check_grace_period_seconds  = number
    image_tag                          = optional(string, "latest") # Add this line
    module_name                        = optional(string, "ecs")
    terraform_managed                  = optional(string, "true")
    blue_deployment_type               = optional(string, "blue")
    green_deployment_type              = optional(string, "green")
    blue_log_stream_prefix             = optional(string, "blue")
    green_log_stream_prefix            = optional(string, "green")
    container_protocol                 = optional(string, "tcp")
    logs_policy_name                   = optional(string, "ecs-logs-policy")
    blue_service_tag_name              = optional(string, "Blue Service")
    green_service_tag_name             = optional(string, "Green Service")
    iam_policy_version                 = optional(string, "2012-10-17")
    iam_service_principal              = optional(string, "ecs-tasks.amazonaws.com")
    network_mode                       = optional(string, "awsvpc")
    requires_compatibilities           = optional(list(string), ["FARGATE"])
    launch_type                        = optional(string, "FARGATE")
    assign_public_ip                   = optional(bool, true)
    green_desired_count                = optional(number, 0)
    enable_fargate_capacity_providers  = optional(bool, false)
    execute_command_logging            = optional(string, "DEFAULT")
    container_essential                = optional(bool, true)
    log_driver                         = optional(string, "awslogs")
    task_execution_policy_arn          = optional(string, "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy")
    green_container_name_suffix        = optional(string, "-green")
    green_task_family_suffix           = optional(string, "-green")
    efs_root_directory_default         = optional(string, "/")
    efs_transit_encryption_default     = optional(string, "ENABLED")
    blue_task_name_suffix              = optional(string, "-blue")
    green_task_name_suffix             = optional(string, "-green")
    log_resource_suffix                = optional(string, ":*")
    application = optional(map(object({
      container_name     = string
      blue_service_name  = string
      green_service_name = string
      task_family        = string
      container_port     = number
    })), {})
    capacity_provider_strategy = optional(list(object({
      capacity_provider = string
      weight            = number
      base              = optional(number)
      })), [
      {
        capacity_provider = "FARGATE"
        weight            = 1
      }
    ])
    container_secrets = optional(list(object({
      name      = string
      valueFrom = string
    })), [])
    task_volumes = optional(list(object({
      name = string
      efs_volume_configuration = optional(object({
        file_system_id          = string
        root_directory          = optional(string)
        transit_encryption      = optional(string)
        transit_encryption_port = optional(number)
      }))
    })), [])
  })
  default = {
    cluster_name                       = "blue-green-cluster"
    task_family                        = "blue-green-task"
    task_role_arn                      = null
    cpu                                = "256"
    memory                             = "512"
    container_name                     = "blue-green-container"
    container_port                     = 80
    desired_count                      = 1
    execution_role_name                = "ecs-task-execution-role"
    blue_service_name                  = "blue-service"
    green_service_name                 = "green-service"
    image_tag                          = "latest" # Already added
    task_definition                    = "blue-green-task-def"
    enable_container_insights          = false
    enable_container_logs              = false
    log_group_name                     = "/ecs/blue-green-app"
    log_retention_days                 = 30
    container_environment              = []
    deployment_maximum_percent         = 200
    deployment_minimum_healthy_percent = 100
    health_check_grace_period_seconds  = 60
    module_name                        = "ecs"
    terraform_managed                  = "true"
    blue_deployment_type               = "blue"
    green_deployment_type              = "green"
    blue_log_stream_prefix             = "blue"
    green_log_stream_prefix            = "green"
    container_protocol                 = "tcp"
    logs_policy_name                   = "ecs-logs-policy"
    blue_service_tag_name              = "Blue Service"
    green_service_tag_name             = "Green Service"
    iam_policy_version                 = "2012-10-17"
    iam_service_principal              = "ecs-tasks.amazonaws.com"
    network_mode                       = "awsvpc"
    requires_compatibilities           = ["FARGATE"]
    launch_type                        = "FARGATE"
    assign_public_ip                   = true
    green_desired_count                = 0
    enable_fargate_capacity_providers  = false
    execute_command_logging            = "DEFAULT"
    container_essential                = true
    log_driver                         = "awslogs"
    task_execution_policy_arn          = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
    green_container_name_suffix        = "-green"
    green_task_family_suffix           = "-green"
    efs_root_directory_default         = "/"
    efs_transit_encryption_default     = "ENABLED"
    blue_task_name_suffix              = "-blue"
    green_task_name_suffix             = "-green"
    log_resource_suffix                = ":*"
    application                        = {}
    capacity_provider_strategy = [
      {
        capacity_provider = "FARGATE"
        weight            = 1
      }
    ]
    container_secrets = []
    task_volumes      = []
  }
}

############################################
# EC2 Configuration Variables
############################################

variable "ec2" {
  description = "EC2 configuration settings"
  type = object({
    key_name           = string
    private_key_base64 = string
    public_key_path    = string
    instance_type      = string
    ami_id             = string
    environment_tag    = string
    ssh_user           = string
    common_var1        = string
    application = map(object({
      blue_instance_name  = string
      green_instance_name = string
    }))
    additional_tags  = map(string)
    user_data_script = optional(string, null)
  })
}


############################################
# ASG Configuration Variables
############################################

variable "asg" {
  description = "Auto Scaling Group configuration settings"
  type = object({
    name                        = string
    min_size                    = number
    max_size                    = number
    desired_capacity            = number
    launch_template_name_prefix = string
    associate_public_ip_address = bool
    health_check_type           = string
    health_check_grace_period   = number
    min_healthy_percentage      = number
    instance_warmup             = number
    module_name                 = optional(string, "asg")
    terraform_managed           = optional(string, "true")
  })
  default = {
    name                        = "blue_green_asg"
    min_size                    = 1
    max_size                    = 2
    desired_capacity            = 1
    launch_template_name_prefix = "blue-green-launch-template"
    associate_public_ip_address = true
    health_check_type           = "ELB"
    health_check_grace_period   = 300
    min_healthy_percentage      = 50
    instance_warmup             = 60
    module_name                 = "asg"
    terraform_managed           = "true"
  }
}

############################################
# Pipeline Integration Variables
############################################

variable "pipeline" {
  description = "Pipeline integration settings"
  type = object({
    blue_target_group_arn  = string
    green_target_group_arn = string
  })
  default = {
    blue_target_group_arn  = null
    green_target_group_arn = null
  }
}


variable "common_var1" {
  description = "Common variable shared across applications"
  type        = string
  default     = ""
}
