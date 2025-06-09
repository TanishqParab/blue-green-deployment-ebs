variable "subnet_id" {
  description = "Subnet ID where EC2 instances will be launched"
  type        = string
}

variable "security_group_id" {
  description = "Security group ID to associate with EC2 instances"
  type        = string
}

variable "key_name" {
  description = "SSH key name for EC2 instances"
  type        = string
}

variable "private_key_base64" {
  description = "Base64 encoded private key"
  type        = string
}

variable "instance_type" {
  description = "EC2 instance type"
  type        = string
  default     = "t3.micro"
}

variable "ami_id" {
  description = "Amazon Machine Image (AMI) ID"
  type        = string
  default     = "ami-05b10e08d247fb927"
}

variable "public_key_path" {
  description = "Path to the public SSH key"
  type        = string
}

variable "environment_tag" {
  description = "Environment tag for EC2 instances"
  type        = string
  default     = "Blue-Green"
}

variable "ssh_user" {
  description = "SSH user for connecting to instances"
  type        = string
  default     = "ec2-user"
}

variable "additional_tags" {
  description = "Additional tags for EC2 instances"
  type        = map(string)
  default     = {}
}

variable "install_dependencies_script_path" {
  description = "Path to the install dependencies script"
  type        = string
  default     = "scripts/install_dependencies.sh"
}

variable "jenkins_file_path" {
  description = "Path to the Jenkinsfile"
  type        = string
  default     = "Jenkinsfile"
}

/*
variable "apps" {
  description = "Configuration for multiple applications to deploy with blue-green strategy"
  type = list(object({
    name                = string
    blue_instance_name  = string
    green_instance_name = string
    app_script_path     = string
    port                = number
  }))
  default = [
    {
      name                = "default-app"
      blue_instance_name  = "Blue-Instance"
      green_instance_name = "Green-Instance"
      app_script_path     = "scripts/app.py"
      port                = 5000
    }
  ]
}


variable "app_id" {
  description = "Application identifier for tagging"
  type        = string
  default     = "default-app"
}

*/

variable "application" {
  description = "Map of application configurations for blue-green deployment"
  type = map(object({
    blue_instance_name  = string
    green_instance_name = string
  }))
  default = {
    default = {
      blue_instance_name  = "Blue-Instance"
      green_instance_name = "Green-Instance"
    }
  }
}
