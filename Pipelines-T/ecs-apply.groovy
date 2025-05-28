// ecs-apply.groovy

@Library('jenkins-shared-library') _

// Call the ECS pipeline with custom configuration
ecsPipeline([
    tfWorkingDir: "/var/lib/jenkins/workspace/blue-green-deployment-job/blue-green-deployment",
    emailRecipient: "tanishqparab2001@gmail.com",
    ecrRepoName: "blue-green-app",
    varFile: "terraform.tfvars"
])
