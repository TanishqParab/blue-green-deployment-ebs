// ec2-apply.groovy

@Library('jenkins-shared-library') _

// Call the EC2 pipeline with custom configuration
ec2Pipeline([
    tfWorkingDir: "/var/lib/jenkins/workspace/blue-green-deployment-job-apply/blue-green-deployment",
    emailRecipient: "tanishqparab2001@gmail.com",
    sshKeyId: "blue-green-key",
    varFile: "terraform.tfvars"
])
