output "cluster_id" {
  description = "The ID of the ECS cluster"
  value       = aws_ecs_cluster.blue_green_cluster.id
}

output "cluster_arn" {
  description = "The ARN of the ECS cluster"
  value       = aws_ecs_cluster.blue_green_cluster.arn
}

output "cluster_name" {
  description = "The name of the ECS cluster"
  value       = aws_ecs_cluster.blue_green_cluster.name
}

output "blue_task_definition_arn" {
  description = "The ARN of the blue task definition"
  value       = aws_ecs_task_definition.blue_task.arn
}

output "green_task_definition_arn" {
  description = "The ARN of the green task definition"
  value       = aws_ecs_task_definition.green_task.arn
}

output "blue_service_id" {
  description = "The ID of the blue service"
  value       = aws_ecs_service.blue_service.id
}

output "blue_service_name" {
  description = "The name of the blue service"
  value       = aws_ecs_service.blue_service.name
}

output "green_service_id" {
  description = "The ID of the green service"
  value       = aws_ecs_service.green_service.id
}

output "green_service_name" {
  description = "The name of the green service"
  value       = aws_ecs_service.green_service.name
}

output "execution_role_arn" {
  description = "The ARN of the ECS task execution role"
  value       = aws_iam_role.ecs_task_execution_role.arn
}

output "execution_role_name" {
  description = "The name of the ECS task execution role"
  value       = aws_iam_role.ecs_task_execution_role.name
}

output "log_group_arn" {
  description = "The ARN of the CloudWatch log group for container logs"
  value       = var.enable_container_logs ? aws_cloudwatch_log_group.ecs_logs[0].arn : null
}

output "log_group_name" {
  description = "The name of the CloudWatch log group for container logs"
  value       = var.enable_container_logs ? aws_cloudwatch_log_group.ecs_logs[0].name : null
}

output "services" {
  description = "Map of services created and their attributes"
  value = {
    blue = {
      id                = aws_ecs_service.blue_service.id
      name              = aws_ecs_service.blue_service.name
      task_definition   = aws_ecs_task_definition.blue_task.arn
      desired_count     = aws_ecs_service.blue_service.desired_count
      target_group_arn  = var.blue_target_group_arn
    }
    green = {
      id                = aws_ecs_service.green_service.id
      name              = aws_ecs_service.green_service.name
      task_definition   = aws_ecs_task_definition.green_task.arn
      desired_count     = aws_ecs_service.green_service.desired_count
      target_group_arn  = var.green_target_group_arn
    }
  }
}