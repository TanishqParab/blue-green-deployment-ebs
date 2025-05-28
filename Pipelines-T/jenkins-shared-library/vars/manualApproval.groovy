// vars/manualApproval.groovy

def call(config) {
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
