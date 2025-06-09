output "alb_id" {
  description = "The ID of the ALB"
  value       = aws_lb.main.id
}

output "alb_arn" {
  description = "The ARN of the ALB"
  value       = aws_lb.main.arn
}

output "alb_dns_name" {
  description = "The DNS name of the ALB"
  value       = aws_lb.main.dns_name
}

output "alb_zone_id" {
  description = "The zone ID of the ALB"
  value       = aws_lb.main.zone_id
}

output "http_listener_arn" {
  description = "The ARN of the HTTP listener"
  value       = aws_lb_listener.http.arn
}

output "https_listener_arn" {
  description = "The ARN of the HTTPS listener"
  value       = var.create_https_listener ? aws_lb_listener.https[0].arn : null
}

output "blue_target_group_arns" {
  description = "Map of ARNs of the blue target groups"
  value       = { for k, v in aws_lb_target_group.blue : k => v.arn }
}

output "green_target_group_arns" {
  description = "Map of ARNs of the green target groups"
  value       = { for k, v in aws_lb_target_group.green : k => v.arn }
}

output "blue_target_group_names" {
  description = "Map of names of the blue target groups"
  value       = { for k, v in aws_lb_target_group.blue : k => v.name }
}

output "green_target_group_names" {
  description = "Map of names of the green target groups"
  value       = { for k, v in aws_lb_target_group.green : k => v.name }
}