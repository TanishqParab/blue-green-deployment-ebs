// vars/ec2RollbackPipeline.groovy

def call(Map config) {
    rollbackPipeline([
        implementation: 'ec2',
        awsRegion: config.awsRegion ?: 'us-east-1',
        awsCredentialsId: config.awsCredentialsId ?: 'aws-credentials',
        tfWorkingDir: config.tfWorkingDir,
        sshKeyId: config.sshKeyId,
        appFile: config.appFile ?: 'app.py',
        emailRecipient: config.emailRecipient,
        repoBranch: config.repoBranch ?: 'main',
        repoUrl: config.repoUrl
    ])
}
