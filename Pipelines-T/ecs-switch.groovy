// ecs-switch.Jenkinsfile

@Library('jenkins-shared-library') _

// Call the ECS switch pipeline with custom configuration
ecsSwitchPipeline([
    tfWorkingDir: "/var/lib/jenkins/workspace/blue-green-deployment-job-ecs-switch-test/blue-green-deployment",
    emailRecipient: "tanishqparab2001@gmail.com",
    ecrRepoName: "blue-green-app"
])
