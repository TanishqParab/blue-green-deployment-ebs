### EC2 Module variables.tf

variable "subnet_id" {
  description = "Subnet ID where EC2 instances will be launched"
  type        = string
}

variable "ec2_security_group_id" {
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