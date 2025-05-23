/*

variable "ecs_task_execution_role_name" {
  description = "The name of the ECS Task Execution Role"
  type        = string
  default     = "ecsTaskExecutionRole"
}

variable "ecs_task_role_name" {
  description = "The name of the ECS Task Role"
  type        = string
  default     = "ecsTaskRole"
}

variable "ecs_task_execution_policy_arn" {
  description = "The ARN of the policy to attach to the ECS Task Execution Role"
  type        = string
  default     = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

variable "ecs_task_role_policies" {
  description = "List of additional IAM policies to attach to ECS Task Role"
  type        = list(string)
  default     = []
}

*/
