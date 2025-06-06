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

output "blue_task_definition_arns" {
  description = "Map of ARNs of the blue task definitions"
  value       = { for k, v in aws_ecs_task_definition.blue_task : k => v.arn }
}

output "green_task_definition_arns" {
  description = "Map of ARNs of the green task definitions"
  value       = { for k, v in aws_ecs_task_definition.green_task : k => v.arn }
}

output "blue_service_ids" {
  description = "Map of IDs of the blue services"
  value       = { for k, v in aws_ecs_service.blue_service : k => v.id }
}

output "blue_service_names" {
  description = "Map of names of the blue services"
  value       = { for k, v in aws_ecs_service.blue_service : k => v.name }
}

output "green_service_ids" {
  description = "Map of IDs of the green services"
  value       = { for k, v in aws_ecs_service.green_service : k => v.id }
}

output "green_service_names" {
  description = "Map of names of the green services"
  value       = { for k, v in aws_ecs_service.green_service : k => v.name }
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
    for app_key, app in var.application : app_key => {
      blue = {
        id               = aws_ecs_service.blue_service[app_key].id
        name             = aws_ecs_service.blue_service[app_key].name
        task_definition  = aws_ecs_task_definition.blue_task[app_key].arn
        desired_count    = aws_ecs_service.blue_service[app_key].desired_count
        target_group_arn = var.blue_target_group_arn
      }
      green = {
        id               = aws_ecs_service.green_service[app_key].id
        name             = aws_ecs_service.green_service[app_key].name
        task_definition  = aws_ecs_task_definition.green_task[app_key].arn
        desired_count    = aws_ecs_service.green_service[app_key].desired_count
        target_group_arn = var.green_target_group_arn
      }
    }
  }
}