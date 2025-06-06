<#

param(
    [string]$AwsRegion,
    [string]$DockerfilePath,
    [string]$ImageName,
    [string]$RepositoryUrl
)

# Authenticate Docker to ECR
Write-Host "Authenticating to ECR..."
aws ecr get-login-password --region $AwsRegion | docker login --username AWS --password-stdin $RepositoryUrl

# Navigate to the directory with Dockerfile
Write-Host "Navigating to directory with Dockerfile..."
$DockerfileDir = Split-Path -Parent $DockerfilePath
cd $DockerfileDir

# Build the Docker image
Write-Host "Building Docker image..."
docker build -t $ImageName .

# Tag the image
Write-Host "Tagging Docker image..."
docker tag "${ImageName}:latest" "${RepositoryUrl}:latest"

# Push the image
Write-Host "Pushing Docker image to ECR..."
docker push "${RepositoryUrl}:latest"

Write-Host "Docker image successfully built and pushed to ECR."


#>