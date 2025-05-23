variable "vpc_id" {
  description = "VPC ID where the ALB will be created"
  type        = string
}

variable "public_subnet_ids" {
  description = "List of subnet IDs for the ALB"
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group ID to associate with ECS tasks"
  type        = string
}

variable "security_groups" {
  description = "List of security group IDs to assign to the ALB (overrides ecs_security_group_id if provided)"
  type        = list(string)
  default     = null
}

variable "listener_port" {
  description = "Port for the ALB listener"
  type        = number
  default     = 80
}

variable "listener_protocol" {
  description = "Protocol for the ALB listener (HTTP or HTTPS)"
  type        = string
  default     = "HTTP"
  validation {
    condition     = contains(["HTTP", "HTTPS"], var.listener_protocol)
    error_message = "Listener protocol must be either HTTP or HTTPS."
  }
}

variable "health_check_path" {
  description = "The health check path for the target groups"
  type        = string
  default     = "/health"
}

variable "health_check_interval" {
  description = "The interval (in seconds) between health checks"
  type        = number
  default     = 30
}

variable "health_check_timeout" {
  description = "The timeout (in seconds) for each health check"
  type        = number
  default     = 10
}

variable "healthy_threshold" {
  description = "The number of successful health checks required to mark a target as healthy"
  type        = number
  default     = 3
}

variable "unhealthy_threshold" {
  description = "The number of failed health checks required to mark a target as unhealthy"
  type        = number
  default     = 2
}

variable "health_check_port" {
  description = "The port to use for health checks"
  type        = string
  default     = "traffic-port"
}

variable "health_check_protocol" {
  description = "The protocol to use for health checks"
  type        = string
  default     = "HTTP"
}

variable "alb_name" {
  description = "Name of the Application Load Balancer"
  type        = string
  default     = "blue-green-alb"
}

variable "internal" {
  description = "Whether the ALB is internal or internet-facing"
  type        = bool
  default     = false
}

variable "load_balancer_type" {
  description = "Type of load balancer"
  type        = string
  default     = "application"
}

variable "blue_target_group_name" {
  description = "Name of the blue target group"
  type        = string
  default     = "blue-tg"
}

variable "green_target_group_name" {
  description = "Name of the green target group"
  type        = string
  default     = "green-tg"
}

variable "target_group_port" {
  description = "Port for the target groups"
  type        = number
  default     = 5000
}

variable "target_group_protocol" {
  description = "Protocol for the target groups"
  type        = string
  default     = "HTTP"
}

variable "target_type" {
  description = "Type of target for the target groups"
  type        = string
  default     = "ip"
}

variable "health_check_matcher" {
  description = "HTTP codes to use when checking for a successful response from a target"
  type        = string
  default     = "200"
}

variable "stickiness_enabled" {
  description = "Whether stickiness is enabled for the load balancer"
  type        = bool
  default     = true
}

variable "stickiness_duration" {
  description = "Duration (in seconds) for stickiness"
  type        = number
  default     = 300
}

variable "blue_weight" {
  description = "Initial weight for the blue target group"
  type        = number
  default     = 100
}

variable "green_weight" {
  description = "Initial weight for the green target group"
  type        = number
  default     = 0
}

variable "additional_tags" {
  description = "Additional tags for the ALB resources"
  type        = map(string)
  default     = {}
}

variable "environment" {
  description = "Environment name (e.g., dev, staging, prod)"
  type        = string
  default     = "dev"
}

variable "module_name" {
  description = "Name of the module for tagging"
  type        = string
  default     = "alb"
}

variable "terraform_managed" {
  description = "Indicates if the resource is managed by Terraform"
  type        = string
  default     = "true"
}

variable "blue_deployment_group" {
  description = "Deployment group name for blue target group"
  type        = string
  default     = "blue"
}

variable "green_deployment_group" {
  description = "Deployment group name for green target group"
  type        = string
  default     = "green"
}

variable "enable_access_logs" {
  description = "Enable access logs for the ALB"
  type        = bool
  default     = false
}

variable "access_logs_bucket" {
  description = "S3 bucket for ALB access logs"
  type        = string
  default     = ""
}

variable "access_logs_prefix" {
  description = "S3 bucket prefix for ALB access logs"
  type        = string
  default     = "alb-logs"
}

variable "idle_timeout" {
  description = "The time in seconds that the connection is allowed to be idle"
  type        = number
  default     = 60
}

variable "enable_deletion_protection" {
  description = "Enable deletion protection for the ALB"
  type        = bool
  default     = false
}

variable "drop_invalid_header_fields" {
  description = "Indicates whether HTTP headers with header fields that are not valid are removed by the load balancer"
  type        = bool
  default     = false
}

variable "deregistration_delay" {
  description = "Amount of time for Elastic Load Balancing to wait before changing the state of a deregistering target from draining to unused"
  type        = number
  default     = 300
}

variable "target_group_stickiness_enabled" {
  description = "Boolean to enable / disable stickiness on the target group level"
  type        = bool
  default     = false
}

variable "target_group_stickiness_type" {
  description = "The type of stickiness (lb_cookie or app_cookie)"
  type        = string
  default     = "lb_cookie"
}

variable "target_group_stickiness_duration" {
  description = "The time period, in seconds, during which requests from a client should be routed to the same target"
  type        = number
  default     = 86400 # 1 day
}

variable "create_https_listener" {
  description = "Whether to create an HTTPS listener"
  type        = bool
  default     = false
}

variable "https_port" {
  description = "Port for HTTPS listener"
  type        = number
  default     = 443
}

variable "ssl_policy" {
  description = "SSL Policy for HTTPS listener"
  type        = string
  default     = "ELBSecurityPolicy-2016-08"
}

variable "certificate_arn" {
  description = "ARN of the SSL certificate for HTTPS listener"
  type        = string
  default     = ""
}

variable "https_protocol" {
  description = "Protocol for HTTPS listener"
  type        = string
  default     = "HTTPS"
}

variable "access_logs_enabled" {
  description = "Whether access logs are enabled"
  type        = bool
  default     = true
}

variable "forward_action_type" {
  description = "Type of action for the default listener action"
  type        = string
  default     = "forward"
}
