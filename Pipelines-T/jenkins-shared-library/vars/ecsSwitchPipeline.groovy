// vars/ecsSwitchPipeline.groovy

def call(Map config = [:]) {
    // Default ECS-specific configurations
    def defaults = [
        implementation: 'ecs',
        awsRegion: 'us-east-1',
        awsCredentialsId: 'aws-credentials',
        tfWorkingDir: "/var/lib/jenkins/workspace/blue-green-deployment-job-ecs-switch-test/blue-green-deployment",
        repoUrl: 'https://github.com/TanishqParab/blue-green-deployment-ecs-test',
        repoBranch: 'main',
        emailRecipient: 'tanishqparab2001@gmail.com',
        appFile: 'app.py',
        dockerfile: 'Dockerfile',
        ecrRepoName: 'blue-green-app',
        containerName: 'blue-green-container',
        containerPort: '80'
    ]
    
    // Merge with user-provided config
    config = defaults + config
    
    // Call the base pipeline with ECS config
    return switchPipeline(config)
}
