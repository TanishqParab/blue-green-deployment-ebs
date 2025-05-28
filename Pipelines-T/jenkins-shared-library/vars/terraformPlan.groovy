// vars/terraformPlan.groovy

def call(config) {
    def tgExist = true
    def blueTG = ""
    def greenTG = ""

    try {
        blueTG = sh(
            script: "aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --region ${config.awsRegion} --output text",
            returnStdout: true
        ).trim()
        greenTG = sh(
            script: "aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --region ${config.awsRegion} --output text",
            returnStdout: true
        ).trim()
    } catch (Exception e) {
        echo "⚠️ Could not fetch TG ARNs. Assuming first build. Skipping TG vars in plan."
        tgExist = false
    }

    def planCommand = "terraform plan"
    if (config.varFile) {
        planCommand += " -var-file=${config.varFile}"
    }

    if (tgExist) {
        if (config.implementation == 'ecs') {
            planCommand += " -var='pipeline.blue_target_group_arn=${blueTG}' -var='pipeline.green_target_group_arn=${greenTG}'"
        } else {
            planCommand += " -var='blue_target_group_arn=${blueTG}' -var='green_target_group_arn=${greenTG}'"
        }
    }

    planCommand += " -out=tfplan"

    echo "Running Terraform Plan: ${planCommand}"
    dir("${config.tfWorkingDir}") {
        sh "${planCommand}"
        archiveArtifacts artifacts: 'tfplan', onlyIfSuccessful: true
    }
}
