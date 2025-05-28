// resources/templates/ecs-apply-pipeline.groovy

def call(Map config = [:]) {
    // Default ECS-specific configurations
    def ecsConfig = [
        implementation: 'ecs',
        repoUrl: 'https://github.com/TanishqParab/blue-green-deployment-ecs-test',
        appFile: 'app.py',
        dockerfile: 'Dockerfile',
        ecrRepoName: 'blue-green-app',
        varFile: 'terraform.tfvars'
    ]
    
    // Merge with user-provided config
    config = ecsConfig + config
    
    // Call the base pipeline with ECS config
    return baseTemplate(config)
}
