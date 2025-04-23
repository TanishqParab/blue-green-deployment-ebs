output "blue_environment_url" {
  description = "URL of the Blue Elastic Beanstalk environment"
  value       = aws_elastic_beanstalk_environment.blue.endpoint_url
}

output "green_environment_url" {
  description = "URL of the Green Elastic Beanstalk environment"
  value       = aws_elastic_beanstalk_environment.green.endpoint_url
}

// In the eb module
output "ec2_sg_id" {
  value = var.ec2_sg_id
}
