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
        def appInfo = config.appName ? " for app ${config.appName}" : ""

        emailext (
            to: config.emailRecipient,
            subject: "Approval required for Terraform apply${appInfo} - Build ${currentBuild.number}",
            body: """
                Hi,

                A Terraform apply${appInfo} requires your approval.

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
                message: "Terraform Apply Approval Required${appInfo}",
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
    def appInfo = config.appName ? " for app ${config.appName}" : ""

    emailext (
        to: config.emailRecipient,
        subject: "🚨 Approval Required for Terraform Destroy${appInfo} - Build ${currentBuild.number}",
        body: """
        WARNING: You are about to destroy AWS infrastructure${appInfo}.

        👉 Click the link below to approve destruction:

        ${destroyLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "⚠️ Confirm destruction of infrastructure${appInfo}?", ok: 'Destroy Now'
    }
}

def switchTrafficApprovalEC2(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    def appInfo = config.appName ? " for app ${config.appName}" : ""
    
    emailext (
        to: config.emailRecipient,
        subject: "Approval required to switch traffic${appInfo} - Build ${currentBuild.number}",
        body: """
            Please review the deployment and approve to switch traffic to the BLUE target group${appInfo}.
            
            🔗 Click here to approve: ${buildLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "Do you want to switch traffic to the new BLUE deployment${appInfo}?", ok: 'Switch Traffic'
    }
}

def switchTrafficApprovalECS(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    def appInfo = config.appName ? " for app ${config.appName}" : ""
    
    // Determine test endpoint based on app
    def testPath = ""
    if (config.appName && config.appName != "app_1") {
        def appSuffix = config.appName.replace("app_", "")
        testPath = "/app${appSuffix}/test"
    } else {
        testPath = "/test"
    }
    
    emailext (
        to: config.emailRecipient,
        subject: "Approval required to switch traffic${appInfo} - Build ${currentBuild.number}",
        body: """
            Please review the deployment and approve to switch traffic${appInfo}.

            Current LIVE environment: ${env.LIVE_ENV}
            New environment to make LIVE: ${env.IDLE_ENV}

            You can test the new version at: http://${env.ALB_DNS}${testPath}

            🔗 Click here to approve: ${buildLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "Do you want to switch traffic from ${env.LIVE_ENV} to ${env.IDLE_ENV}${appInfo}?", ok: 'Switch Traffic'
    }
}

def rollbackApprovalEC2(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    def appInfo = config.appName ? " for app ${config.appName}" : ""
    
    emailext (
        to: config.emailRecipient,
        subject: "🛑 Approval required for ROLLBACK${appInfo} - Build #${currentBuild.number}",
        body: "A rollback has been triggered${appInfo}. Please review and approve the rollback at: ${buildLink}",
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "🚨 Confirm rollback${appInfo} and approve execution", ok: 'Rollback'
    }
}

def rollbackApprovalECS(Map config) {
    def buildLink = "${env.BUILD_URL}input"
    def appInfo = config.appName ? " for app ${config.appName}" : ""
    
    // Determine test endpoint based on app
    def testPath = ""
    if (config.appName && config.appName != "app_1") {
        def appSuffix = config.appName.replace("app_", "")
        testPath = "/app${appSuffix}/test"
    } else {
        testPath = "/test"
    }
    
    emailext (
        to: config.emailRecipient,
        subject: "Approval required for rollback${appInfo} - Build ${currentBuild.number}",
        body: """
            Please review the rollback deployment and approve to switch traffic${appInfo}.
            
            Current LIVE environment: ${env.CURRENT_ENV}
            Environment to rollback to: ${env.ROLLBACK_ENV}
            Previous version image: ${env.ROLLBACK_IMAGE}
            
            You can test the rollback version at: http://${env.ALB_DNS}${testPath}
            
            🔗 Click here to approve: ${buildLink}
        """,
        replyTo: config.emailRecipient
    )

    timeout(time: 1, unit: 'HOURS') {
        input message: "Do you want to rollback from ${env.CURRENT_ENV} to ${env.ROLLBACK_ENV}${appInfo}?", ok: 'Confirm Rollback'
    }
}