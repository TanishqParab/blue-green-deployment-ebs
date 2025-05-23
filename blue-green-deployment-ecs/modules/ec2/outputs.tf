output "blue_instance_ip" {
  description = "Public IP address of the blue instance"
  value       = aws_instance.blue.public_ip
}

output "green_instance_ip" {
  description = "Public IP address of the green instance"
  value       = aws_instance.green.public_ip
}

output "blue_instance_id" {
  description = "ID of the blue instance"
  value       = aws_instance.blue.id
}

output "green_instance_id" {
  description = "ID of the green instance"
  value       = aws_instance.green.id
}

output "blue_instance_arn" {
  description = "ARN of the blue instance"
  value       = aws_instance.blue.arn
}

output "green_instance_arn" {
  description = "ARN of the green instance"
  value       = aws_instance.green.arn
}

output "blue_instance_private_ip" {
  description = "Private IP address of the blue instance"
  value       = aws_instance.blue.private_ip
}

output "green_instance_private_ip" {
  description = "Private IP address of the green instance"
  value       = aws_instance.green.private_ip
}