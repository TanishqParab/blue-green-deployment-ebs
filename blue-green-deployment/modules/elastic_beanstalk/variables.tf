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
