output "repository_url" {
  description = "The URL of the ECR repository"
  value       = aws_ecr_repository.app_repo.repository_url
}

output "repository_name" {
  description = "The name of the ECR repository"
  value       = aws_ecr_repository.app_repo.name
}

output "image_urls" {
  description = "Map of URLs of the built Docker images"
  value       = { for k, v in var.application : k => "${aws_ecr_repository.app_repo.repository_url}:${k}" }
}