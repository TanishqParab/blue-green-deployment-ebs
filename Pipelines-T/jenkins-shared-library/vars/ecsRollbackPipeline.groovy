// vars/ecsRollbackPipeline.groovy

def call(Map config) {
    rollbackPipeline([
        implementation: 'ecs',
        awsRegion: config.awsRegion ?: 'us-east-1',
        awsCredentialsId: config.awsCredentialsId ?: 'aws-credentials',
        tfWorkingDir: config.tfWorkingDir,
        ecrRepoName: config.ecrRepoName,
        containerName: config.containerName ?: 'blue-green-container',
        containerPort: config.containerPort ?: '80',
        dockerfile: config.dockerfile ?: 'Dockerfile',
        emailRecipient: config.emailRecipient,
        repoBranch: config.repoBranch ?: 'main',
        repoUrl: config.repoUrl
    ])
}
