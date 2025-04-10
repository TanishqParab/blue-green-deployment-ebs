output "alb_dns_name" {
  value = aws_lb.main.dns_name
}


output "alb_arn" {
  description = "ARN of the ALB"
  value       = aws_lb.main.arn
}


output "blue_target_group_arn" {
  value = aws_lb_target_group.blue.arn
}

output "green_target_group_arn" {
  value = aws_lb_target_group.green.arn
}