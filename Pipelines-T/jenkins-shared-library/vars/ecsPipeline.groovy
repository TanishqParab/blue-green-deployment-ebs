// vars/ecsPipeline.groovy

def call(Map config = [:]) {
    // Default ECS-specific configurations
    def ecsConfig = [
        implementation: 'ecs',
        awsRegion: 'us-east-1',
        awsCredentialsId: 'aws-credentials',
        tfWorkingDir: "/var/lib/jenkins/workspace/blue-green-deployment-job-ecs-test-apply/blue-green-deployment",
        repoUrl: 'https://github.com/TanishqParab/blue-green-deployment-ecs-test',
        repoBranch: 'main',
        emailRecipient: 'tanishqparab2001@gmail.com',
        appFile: 'app.py',
        dockerfile: 'Dockerfile',
        ecrRepoName: 'blue-green-app',
        varFile: 'terraform.tfvars'
    ]
    
    // Merge with user-provided config
    config = ecsConfig + config
    
    // Call the base pipeline with ECS config
    return basePipeline(config)
}
