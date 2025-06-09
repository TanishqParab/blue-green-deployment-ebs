variable "alb_name" {
  description = "Name of the ALB"
  type        = string
}

variable "internal" {
  description = "Whether the ALB is internal"
  type        = bool
  default     = false
}

variable "load_balancer_type" {
  description = "Type of load balancer"
  type        = string
  default     = "application"
}

variable "security_group_id" {
  description = "Security group ID for the ALB"
  type        = string
}

variable "security_groups" {
  description = "List of security group IDs for the ALB"
  type        = list(string)
  default     = null
}

variable "public_subnet_ids" {
  description = "List of public subnet IDs"
  type        = list(string)
}

variable "vpc_id" {
  description = "VPC ID"
  type        = string
}

variable "listener_port" {
  description = "Port for the listener"
  type        = number
  default     = 80
}

variable "listener_protocol" {
  description = "Protocol for the listener"
  type        = string
  default     = "HTTP"
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
  description = "Port for the target group"
  type        = number
  default     = 80
}

variable "target_group_protocol" {
  description = "Protocol for the target group"
  type        = string
  default     = "HTTP"
}

variable "target_type" {
  description = "Type of target for the target group"
  type        = string
  default     = "ip"
}

variable "deregistration_delay" {
  description = "Deregistration delay for the target group"
  type        = number
  default     = 300
}

variable "health_check_path" {
  description = "Path for health check"
  type        = string
  default     = "/health"
}

variable "health_check_interval" {
  description = "Interval for health check"
  type        = number
  default     = 30
}

variable "health_check_timeout" {
  description = "Timeout for health check"
  type        = number
  default     = 10
}

variable "healthy_threshold" {
  description = "Healthy threshold for health check"
  type        = number
  default     = 3
}

variable "unhealthy_threshold" {
  description = "Unhealthy threshold for health check"
  type        = number
  default     = 2
}

variable "health_check_matcher" {
  description = "HTTP codes to use when checking for a successful response from a target"
  type        = string
  default     = "200"
}

variable "health_check_port" {
  description = "Port to use for health check"
  type        = string
  default     = "traffic-port"
}

variable "health_check_protocol" {
  description = "Protocol to use for health check"
  type        = string
  default     = "HTTP"
}

variable "blue_weight" {
  description = "Weight for the blue target group"
  type        = number
  default     = 100
}

variable "green_weight" {
  description = "Weight for the green target group"
  type        = number
  default     = 0
}

variable "stickiness_enabled" {
  description = "Whether stickiness is enabled"
  type        = bool
  default     = true
}

variable "stickiness_duration" {
  description = "Duration for stickiness"
  type        = number
  default     = 300
}

variable "target_group_stickiness_enabled" {
  description = "Whether target group stickiness is enabled"
  type        = bool
  default     = false
}

variable "target_group_stickiness_type" {
  description = "Type of target group stickiness"
  type        = string
  default     = "lb_cookie"
}

variable "target_group_stickiness_duration" {
  description = "Duration for target group stickiness"
  type        = number
  default     = 86400
}

variable "create_https_listener" {
  description = "Whether to create HTTPS listener"
  type        = bool
  default     = false
}

variable "https_port" {
  description = "Port for HTTPS listener"
  type        = number
  default     = 443
}

variable "https_protocol" {
  description = "Protocol for HTTPS listener"
  type        = string
  default     = "HTTPS"
}

variable "ssl_policy" {
  description = "SSL policy for HTTPS listener"
  type        = string
  default     = "ELBSecurityPolicy-2016-08"
}

variable "certificate_arn" {
  description = "ARN of the certificate for HTTPS listener"
  type        = string
  default     = ""
}

variable "enable_access_logs" {
  description = "Whether to enable access logs"
  type        = bool
  default     = false
}

variable "access_logs_bucket" {
  description = "S3 bucket for access logs"
  type        = string
  default     = ""
}

variable "access_logs_prefix" {
  description = "Prefix for access logs"
  type        = string
  default     = "alb-logs"
}

variable "access_logs_enabled" {
  description = "Whether access logs are enabled"
  type        = bool
  default     = true
}

variable "idle_timeout" {
  description = "Idle timeout for the ALB"
  type        = number
  default     = 60
}

variable "enable_deletion_protection" {
  description = "Whether deletion protection is enabled"
  type        = bool
  default     = false
}

variable "drop_invalid_header_fields" {
  description = "Whether to drop invalid header fields"
  type        = bool
  default     = false
}

variable "forward_action_type" {
  description = "Type of forward action"
  type        = string
  default     = "forward"
}

variable "environment" {
  description = "Environment name"
  type        = string
  default     = "dev"
}

variable "module_name" {
  description = "Module name for tagging"
  type        = string
  default     = "alb"
}

variable "terraform_managed" {
  description = "Whether the resource is managed by Terraform"
  type        = string
  default     = "true"
}

variable "blue_deployment_group" {
  description = "Deployment group for blue resources"
  type        = string
  default     = "blue"
}

variable "green_deployment_group" {
  description = "Deployment group for green resources"
  type        = string
  default     = "green"
}

variable "additional_tags" {
  description = "Additional tags for resources"
  type        = map(string)
  default     = {}
}

# New variables for multi-app support
variable "application_target_groups" {
  description = "Map of application target groups"
  type = map(object({
    blue_target_group_name  = string
    green_target_group_name = string
    target_group_port       = number
  }))
  default = {}
}

variable "application_paths" {
  description = "Map of application paths for routing"
  type = map(object({
    priority     = number
    path_pattern = string
  }))
  default = {}
}