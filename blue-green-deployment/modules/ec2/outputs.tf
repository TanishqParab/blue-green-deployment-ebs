############################################
# EC2 Module Outputs
############################################

output "blue_instance_ids" {
  description = "IDs of the blue instances"
  value       = { for app_name, instance in aws_instance.blue : app_name => instance.id }
}

output "green_instance_ids" {
  description = "IDs of the green instances"
  value       = { for app_name, instance in aws_instance.green : app_name => instance.id }
}

output "blue_instance_public_ips" {
  description = "Public IPs of the blue instances"
  value       = { for app_name, instance in aws_instance.blue : app_name => instance.public_ip }
}

output "green_instance_public_ips" {
  description = "Public IPs of the green instances"
  value       = { for app_name, instance in aws_instance.green : app_name => instance.public_ip }
}

output "blue_instance_private_ips" {
  description = "Private IPs of the blue instances"
  value       = { for app_name, instance in aws_instance.blue : app_name => instance.private_ip }
}

output "green_instance_private_ips" {
  description = "Private IPs of the green instances"
  value       = { for app_name, instance in aws_instance.green : app_name => instance.private_ip }
}

output "app_names" {
  description = "Names of all deployed applications"
  value       = keys(var.application)
}
