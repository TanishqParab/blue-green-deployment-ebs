// vars/ec2Pipeline.groovy

def call(Map config = [:]) {
    // Default EC2-specific configurations
    def ec2Config = [
        implementation: 'ec2',
        awsRegion: 'us-east-1',
        awsCredentialsId: 'aws-credentials',
        tfWorkingDir: "/var/lib/jenkins/workspace/blue-green-deployment-job-apply/blue-green-deployment",
        repoUrl: 'https://github.com/TanishqParab/blue-green-deployment-ecs-test',
        repoBranch: 'main',
        emailRecipient: 'tanishqparab2001@gmail.com',
        sshKeyId: 'blue-green-key',
        appFile: 'app.py'
    ]
    
    // Merge with user-provided config
    config = ec2Config + config
    
    // Call the base pipeline with EC2 config
    return basePipeline(config)
}
