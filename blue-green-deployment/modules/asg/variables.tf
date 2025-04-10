variable "key_name" {
  description = "The name of the key pair to use for the instance"
  type        = string
}

variable "subnet_ids" {
  description = "The list of subnet IDs to launch resources in"
  type        = list(string)
}

/*
variable "alb_target_group_arn" {
  description = "The ARN of the ALB target group"
  type        = string
}*/

variable "security_group_id" {
  description = "The ID of the security group"
  type        = string
}

variable "alb_target_group_arns" {
  description = "List of ALB Target Group ARNs to attach the ASG instances"
  type        = list(string)
  default = [  ]
}

variable "min_size" {
  type    = number
  default = 1
}

variable "max_size" {
  type    = number
  default = 2
}

variable "desired_capacity" {
  type    = number
  default = 1
}

