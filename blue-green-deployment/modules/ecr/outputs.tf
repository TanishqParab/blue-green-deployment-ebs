output "repository_urls" {
  description = "Map of URLs of the ECR repositories"
  value       = { for k, v in aws_ecr_repository.app_repo : k => v.repository_url }
}

output "repository_names" {
  description = "Map of names of the ECR repositories"
  value       = { for k, v in aws_ecr_repository.app_repo : k => v.name }
}

output "image_urls" {
  description = "Map of URLs of the built Docker images"
  value       = { for k, v in var.application : k => "${aws_ecr_repository.app_repo[k].repository_url}:${v.image_tag}" }
}