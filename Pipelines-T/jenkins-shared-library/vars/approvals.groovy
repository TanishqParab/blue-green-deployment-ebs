// vars/approvals.groovy

def terraformApplyApproval(Map config) {
    dir("${config.tfWorkingDir}") {
        def planCmd = 'terraform plan -no-color'
        if (config.varFile) {
            planCmd += " -var-file=${config.varFile}"
        }
        planCmd += " > tfplan.txt"
        sh planCmd

        def tfPlan = readFile('tfplan.txt')
        archiveArtifacts artifacts: 'tfplan.txt', fingerprint: true

        echo "========== Terraform Plan Start =========="
        echo tfPlan
        echo "========== Terraform Plan End ============"

        def planDownloadLink = "${env.BUILD_URL}artifact/tfplan.txt"

        emailext (
            to: config.emailRecipient,
            subject: "Approval required for Terraform apply - Build ${currentBuild.number}",
            body: """
                Hi,

                A Terraform apply requires your approval.

                👉 Review the Terraform plan here:
                ${planDownloadLink}

                Once reviewed, approve/abort at:
                ${env.BUILD_URL}input

                Regards,  
                Jenkins Automation
            """,
            replyTo: config.emailRecipient
        )

        timeout(time: 1, unit: 'HOURS') {
            input(
                id: 'ApplyApproval',
                message: "Terraform Apply Approval Required",
                ok: "Apply",
                parameters: [],
                description: """⚠️ Full plan too long.

✅ Review full plan here:
- [tfplan.txt Artifact](${planDownloadLink})
- Console Output (above this stage)"""
            )
        }
    }
}

def terraformDestroyApproval(Map config) {
    def destroyLink = "${env.BUILD_URL}input"

    emailext (
        to: config.emailRecipient,
        subject: "🚨 Approval Required for Terraform Destroy - Build ${currentBuild.number}",
        body: """
        WARNING: You are about to destroy AWS infrastructure.

        👉 Click the link below to approve destruction:

        ${destroyLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: '⚠️ Confirm destruction of infrastructure?', ok: 'Destroy Now'
    }
}

def switchTrafficApprovalEC2(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    emailext (
        to: config.emailRecipient,
        subject: "Approval required to switch traffic - Build ${currentBuild.number}",
        body: """
            Please review the deployment and approve to switch traffic to the BLUE target group.
            
            🔗 Click here to approve: ${buildLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: 'Do you want to switch traffic to the new BLUE deployment?', ok: 'Switch Traffic'
    }
}

def switchTrafficApprovalECS(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    emailext (
        to: config.emailRecipient,
        subject: "Approval required to switch traffic - Build ${currentBuild.number}",
        body: """
            Please review the deployment and approve to switch traffic.

            Current LIVE environment: ${env.LIVE_ENV}
            New environment to make LIVE: ${env.IDLE_ENV}

            You can test the new version at: http://${env.ALB_DNS}/test

            🔗 Click here to approve: ${buildLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "Do you want to switch traffic from ${env.LIVE_ENV} to ${env.IDLE_ENV}?", ok: 'Switch Traffic'
    }
}

def rollbackApprovalEC2(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    emailext (
        to: config.emailRecipient,
        subject: "🛑 Approval required for ROLLBACK - Build #${currentBuild.number}",
        body: "A rollback has been triggered. Please review and approve the rollback at: ${buildLink}",
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: '🚨 Confirm rollback and approve execution', ok: 'Rollback'
    }
}

def rollbackApprovalECS(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    emailext (
        to: config.emailRecipient,
        subject: "Approval required for rollback - Build ${currentBuild.number}",
        body: """
            Please review the rollback deployment and approve to switch traffic.
            
            Current LIVE environment: ${env.CURRENT_ENV}
            Environment to rollback to: ${env.ROLLBACK_ENV}
            Previous version image: ${env.ROLLBACK_IMAGE}
            
            You can test the rollback version at: http://${env.ALB_DNS}/test
            
            🔗 Click here to approve: ${buildLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "Do you want to rollback from ${env.CURRENT_ENV} to ${env.ROLLBACK_ENV}?", ok: 'Confirm Rollback'
    }
}
