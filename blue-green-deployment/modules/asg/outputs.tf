output "asg_id" {
  description = "The ID of the Auto Scaling Group"
  value       = aws_autoscaling_group.blue_green_asg.id
}

output "asg_name" {
  description = "The name of the Auto Scaling Group"
  value       = aws_autoscaling_group.blue_green_asg.name
}

output "asg_arn" {
  description = "The ARN of the Auto Scaling Group"
  value       = aws_autoscaling_group.blue_green_asg.arn
}

output "launch_template_id" {
  description = "The ID of the launch template"
  value       = aws_launch_template.app.id
}

output "launch_template_arn" {
  description = "The ARN of the launch template"
  value       = aws_launch_template.app.arn
}

output "launch_template_latest_version" {
  description = "The latest version of the launch template"
  value       = aws_launch_template.app.latest_version
}
