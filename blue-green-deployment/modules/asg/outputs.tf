output "asg_id" {
  value = aws_autoscaling_group.blue_green_asg.id
}

output "asg_name" {
  description = "The name of the Auto Scaling Group"
  value       = aws_autoscaling_group.blue_green_asg.name
}

