variable "app_name" {
  description = "Name of the Elastic Beanstalk application"
  type        = string
}

variable "platform_arn" {
  description = "Elastic Beanstalk platform ARN to use (e.g., Python 3.8)"
  type        = string
}

variable "app_zip_path" {
  description = "Path to the application ZIP file to be uploaded to S3"
  type        = string
}

variable "version_label" {
  description = "Version label for the Elastic Beanstalk application version"
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type for both Elastic Beanstalk environments"
  type        = string
}

variable "wsgi_path" {
  description = "WSGI application path (format: 'module:application')"
  type        = string

  validation {
    condition     = can(regex("^\\w+:\\w+$", var.wsgi_path))
    error_message = "WSGI path must be in format 'module:application' (e.g., 'app:app')"
  }
}

variable "health_check_path" {
  description = "Health check endpoint path"
  type        = string
  default     = "/health"
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

variable "ec2_sg_id" {
  description = "Security group ID for Elastic Beanstalk EC2 instances"
  type        = string
  default     = null
}

variable "custom_alb_arn" {
  description = "ARN of the external ALB to attach EB environments to"
  type        = string
  default = null
}

variable "custom_blue_tg_arn" {
  description = "ARN of the blue target group"
  type        = string
  default = null
}

variable "custom_green_tg_arn" {
  description = "ARN of the green target group"
  type        = string
  default = null
}

variable "alb_arn" {
  description = "ARN of the Application Load Balancer"
  type        = string
  default     = null
}

variable "blue_target_group_arn" {
  description = "ARN of the Blue target group"
  type        = string
  default     = null
}

variable "green_target_group_arn" {
  description = "ARN of the Green target group"
  type        = string
  default     = null
}
