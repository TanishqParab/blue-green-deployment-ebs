// vars/pipelineUtils.groovy

def setExecutionType() {
    // Default Execution Type
    env.EXECUTION_TYPE = 'SKIP'

    // Handle Destroy First (Highest Priority)
    if (params.MANUAL_BUILD == 'DESTROY') {
        echo "❌ Destroy requested. Running destroy stage only."
        env.EXECUTION_TYPE = 'DESTROY'
    } 
    // Manual Apply Trigger
    else if (params.MANUAL_BUILD == 'YES') {
        echo "🛠️ Manual build requested. Running Terraform regardless of changes."
        env.EXECUTION_TYPE = 'MANUAL_APPLY'
    }

    echo "Final Execution Type: ${env.EXECUTION_TYPE}"
}

def approveDestroy(Map config) {
    // Final approval URL
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
