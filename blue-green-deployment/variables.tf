variable "aws_region" {
  default = "us-east-1"
}

variable "vpc_cidr" {
  default = "10.0.0.0/16"
}

variable "public_subnet_cidrs" {
  type    = list(string)
  default = ["10.0.1.0/24", "10.0.2.0/24"]
}


variable "key_name" {
  description = "Name of the key pair"
  type        = string
  default     = "blue-green-key-pair"
}

variable "private_key_base64" {
  description = "Base64 encoded private key for SSH"
  type        = string
}

variable "public_key_path" {
  description = "Path to the public key file"
  type        = string
  default     = "/var/lib/jenkins/workspace/blue-green-deployment-job/blue-green-deployment/blue-green-key.pub"
}


variable "availability_zones" {
  type    = list(string)
  default = ["us-east-1a", "us-east-1b"]
}

/*
variable "instance_type" {
  default = "t3.micro"
}
*/

variable "listener_port" {
  type = number
  default = 80
}

variable "blue_target_group_arn" {
  description = "ARN of the Blue target group"
  type        = string
  default     = ""
}

variable "green_target_group_arn" {
  description = "ARN of the Green target group"
  type        = string
  default     = ""
}

variable "min_size" {
  type    = number
  default = 1
}

variable "max_size" {
  type    = number
  default = 1
}

variable "desired_capacity" {
  type    = number
  default = 1
}

variable "health_check_path" {
  description = "The health check path for the target groups."
  type        = string
  default     = "/health"
}

variable "health_check_interval" {
  description = "The interval (in seconds) between health checks."
  type        = number
  default     = 30
}

variable "health_check_timeout" {
  description = "The timeout (in seconds) for each health check."
  type        = number
  default     = 5
}

variable "healthy_threshold" {
  description = "The number of successful health checks required to mark a target as healthy."
  type        = number
  default     = 3
}

variable "unhealthy_threshold" {
  description = "The number of failed health checks required to mark a target as unhealthy."
  type        = number
  default     = 2
}

variable "app_name" {
  description = "Elastic Beanstalk application name"
  type        = string
}

variable "platform_arn" {
  description = "Elastic Beanstalk platform ARN to use (e.g., Python 3.8)"
  type        = string
}

variable "app_zip_path" {
  description = "Path to the application ZIP file"
  type        = string
}

variable "version_label" {
  description = "Label for the Elastic Beanstalk application version"
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type for the Elastic Beanstalk environment"
  type        = string
}

variable "enable_https" {
  description = "Whether to enable HTTPS and custom domain configuration"
  type        = bool
  default     = false
}

variable "wsgi_path" {
  description = "WSGI application path (format: 'module:application')"
  type        = string

  validation {
    condition     = can(regex("^\\w+:\\w+$", var.wsgi_path))
    error_message = "WSGI path must be in format 'module:application' (e.g., 'app:app')"
  }
}

variable "app_source_dir" {
  description = "Path to application source directory containing requirements.txt"
  type        = string
}

variable "s3_bucket" {
  description = "Name of the S3 bucket where application ZIP will be uploaded"
  type        = string
}

variable "blue_env_name" {
  description = "Elastic Beanstalk environment name for the Blue deployment"
  type        = string
}

variable "green_env_name" {
  description = "Elastic Beanstalk environment name for the Green deployment"
  type        = string
}

variable "env_settings" {
  description = "Environment configuration settings to apply to both environments"
  type = list(object({
    namespace = string
    name      = string
    value     = string
  }))
  default = []
}

variable "environment_tier" {
  description = "Environment tier type (e.g., WebServer or Worker)"
  type        = string
  default     = "WebServer"
}

variable "rolling_update_enabled" {
  description = "Whether rolling updates are enabled for the environments"
  type        = bool
  default     = true
}

variable "iam_service_role_arn" {
  description = "IAM service role ARN for Elastic Beanstalk environments"
  type        = string
}

variable "cname_prefix_blue" {
  description = "CNAME prefix for the Blue Elastic Beanstalk environment"
  type        = string
}

variable "cname_prefix_green" {
  description = "CNAME prefix for the Green Elastic Beanstalk environment"
  type        = string
}

