// EC2 Rollback Jenkinsfile

@Library('jenkins-shared-library') _

ec2RollbackPipeline([
    awsRegion: 'us-east-1',
    awsCredentialsId: 'aws-credentials',
    tfWorkingDir: '/var/lib/jenkins/workspace/blue-green-deployment-job-ec2-rollback-test/blue-green-deployment',
    sshKeyId: 'blue-green-key',
    appFile: 'app.py',
    emailRecipient: 'tanishqparab2001@gmail.com',
    repoBranch: 'main',
    repoUrl: 'https://github.com/TanishqParab/blue-green-deployment'
])
