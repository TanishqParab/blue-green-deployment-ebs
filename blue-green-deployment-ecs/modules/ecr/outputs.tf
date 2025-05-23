output "repository_url" {
  description = "The URL of the ECR repository"
  value       = aws_ecr_repository.app_repo.repository_url
}

output "repository_name" {
  description = "The name of the ECR repository"
  value       = aws_ecr_repository.app_repo.name
}

output "image_url" {
  description = "The URL of the built Docker image"
  value       = "${aws_ecr_repository.app_repo.repository_url}:${var.image_tag}"
}
