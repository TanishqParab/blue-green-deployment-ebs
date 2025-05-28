// ECS Rollback Jenkinsfile
@Library('jenkins-shared-library') _

ecsRollbackPipeline([
    awsRegion: 'us-east-1',
    awsCredentialsId: 'aws-credentials',
    tfWorkingDir: '/var/lib/jenkins/workspace/blue-green-deployment-job-ecs-rollback-test/blue-green-deployment',
    ecrRepoName: 'blue-green-app',
    containerName: 'blue-green-container',
    containerPort: '80',
    emailRecipient: 'tanishqparab2001@gmail.com',
    repoBranch: 'main',
    repoUrl: 'https://github.com/TanishqParab/blue-green-deployment-ecs-test'
])
