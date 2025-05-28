// vars/manualDestroyApproval.groovy

def call(config) {
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
