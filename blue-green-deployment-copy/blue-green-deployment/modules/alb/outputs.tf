output "alb_dns_name" {
  value = aws_lb.main.dns_name
}

output "target_group_arn_blue" {
  value = aws_lb_target_group.blue.arn
}

output "target_group_arn_green" {
  value = aws_lb_target_group.green.arn
}