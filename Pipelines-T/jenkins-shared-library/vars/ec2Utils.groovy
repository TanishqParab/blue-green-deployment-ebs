// vars/ec2Utils.groovy


def registerInstancesToTargetGroups(Map config) {
    if (config.implementation != 'ec2' || params.MANUAL_BUILD == 'DESTROY') {
        echo "⚠️ Skipping EC2 registration as conditions not met."
        return
    }

    echo "📥 Fetching Target Group ARNs from AWS..."

    env.BLUE_TG_ARN = sh(
        script: """
        aws elbv2 describe-target-groups --names "blue-tg" --query 'TargetGroups[0].TargetGroupArn' --output text
        """,
        returnStdout: true
    ).trim()

    env.GREEN_TG_ARN = sh(
        script: """
        aws elbv2 describe-target-groups --names "green-tg" --query 'TargetGroups[0].TargetGroupArn' --output text
        """,
        returnStdout: true
    ).trim()

    if (!env.BLUE_TG_ARN || !env.GREEN_TG_ARN) {
        error "❌ Failed to fetch Target Group ARNs! Check if they exist in AWS."
    }

    echo "✅ Blue Target Group ARN: ${env.BLUE_TG_ARN}"
    echo "✅ Green Target Group ARN: ${env.GREEN_TG_ARN}"

    echo "🔍 Fetching EC2 instance IDs..."

    def blueInstanceId = sh(
        script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=Blue-Instance" "Name=instance-state-name,Values=running" \
        --query 'Reservations[0].Instances[0].InstanceId' --output text
        """,
        returnStdout: true
    ).trim()

    def greenInstanceId = sh(
        script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=Green-Instance" "Name=instance-state-name,Values=running" \
        --query 'Reservations[0].Instances[0].InstanceId' --output text
        """,
        returnStdout: true
    ).trim()

    if (!blueInstanceId || !greenInstanceId) {
        error "❌ Blue or Green instance not found! Check AWS console."
    }

    echo "✅ Blue Instance ID: ${blueInstanceId}"
    echo "✅ Green Instance ID: ${greenInstanceId}"

    echo "🔄 Deregistering old instances before re-registering..."
    sh """
    aws elbv2 deregister-targets --target-group-arn ${env.BLUE_TG_ARN} --targets Id=${greenInstanceId}
    aws elbv2 deregister-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${blueInstanceId}
    """
    sleep(10)

    echo "📝 Registering instances to the correct target groups..."
    sh """
    aws elbv2 register-targets --target-group-arn ${env.BLUE_TG_ARN} --targets Id=${blueInstanceId}
    aws elbv2 register-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${greenInstanceId}
    """

    echo "✅ EC2 instances successfully registered to correct target groups!"
}


def detectChanges(Map config) {
    echo "🔍 Detecting changes for EC2 implementation..."

    def changedFiles = sh(script: "git diff --name-only HEAD~1 HEAD", returnStdout: true).trim().split('\n')
    echo "Changed files: ${changedFiles}"

    def onlyAppChange = (changedFiles.length == 1 && changedFiles[0] == "blue-green-deployment-ec2/modules/ec2/scripts/app.py")

    if (onlyAppChange) {
        echo "🚀 Detected only app.py change, executing App Deploy."
        env.EXECUTION_TYPE = 'APP_DEPLOY'
    } else {
        echo "✅ Infra changes detected (excluding app.py), running full deployment."
        env.EXECUTION_TYPE = 'FULL_DEPLOY'
    }
}


def fetchResources(Map config) {
    echo "🔍 Fetching Target Group ARNs..."

    env.BLUE_TG_ARN = sh(
        script: """
        aws elbv2 describe-target-groups --names "blue-tg" --query 'TargetGroups[0].TargetGroupArn' --output text
        """,
        returnStdout: true
    ).trim()

    env.GREEN_TG_ARN = sh(
        script: """
        aws elbv2 describe-target-groups --names "green-tg" --query 'TargetGroups[0].TargetGroupArn' --output text
        """,
        returnStdout: true
    ).trim()

    if (!env.BLUE_TG_ARN || !env.GREEN_TG_ARN) {
        error "❌ Failed to fetch Target Group ARNs! Check if they exist in AWS."
    }

    echo "✅ Blue Target Group ARN: ${env.BLUE_TG_ARN}"
    echo "✅ Green Target Group ARN: ${env.GREEN_TG_ARN}"
}




def updateApplication(Map config) {
    echo "Running EC2 update application logic..."

    // Register Instances to Target Groups
    echo "Registering instances to target groups..."

    def blueInstanceId = sh(
        script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=Blue-Instance" "Name=instance-state-name,Values=running" \
        --query 'Reservations[0].Instances[0].InstanceId' --output text
        """,
        returnStdout: true
    ).trim()

    def greenInstanceId = sh(
        script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=Green-Instance" "Name=instance-state-name,Values=running" \
        --query 'Reservations[0].Instances[0].InstanceId' --output text
        """,
        returnStdout: true
    ).trim()

    if (!blueInstanceId || !greenInstanceId) {
        error "❌ Blue or Green instance not found! Check AWS console."
    }

    echo "✅ Blue Instance ID: ${blueInstanceId}"
    echo "✅ Green Instance ID: ${greenInstanceId}"

    echo "❌ Deregistering old instances before re-registering..."
    sh """
        aws elbv2 deregister-targets --target-group-arn ${env.BLUE_TG_ARN} --targets Id=${greenInstanceId}
        aws elbv2 deregister-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${blueInstanceId}
    """
    sleep(10) // Allow time for deregistration

    echo "✅ Registering instances to the correct target groups..."
    sh """
        aws elbv2 register-targets --target-group-arn ${env.BLUE_TG_ARN} --targets Id=${blueInstanceId}
        aws elbv2 register-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${greenInstanceId}
    """

    echo "✅ Instances successfully registered to correct target groups!"
}

def deployToBlueInstance(Map config) {
    // 1. Dynamically get ALB ARN by ALB name (or partial match)
    def albArn = sh(
        script: """
        aws elbv2 describe-load-balancers \
            --names "${config.albName}" \
            --query 'LoadBalancers[0].LoadBalancerArn' \
            --output text
        """,
        returnStdout: true
    ).trim()

    if (!albArn || albArn == 'None') {
        error "❌ Could not find ALB ARN with name '${config.albName}'"
    }
    echo "✅ Found ALB ARN: ${albArn}"

    // 2. Dynamically get Blue Target Group ARN filtered by ALB ARN and TG name/tag
    def blueTGArn = sh(
        script: """
        aws elbv2 describe-target-groups \
            --load-balancer-arn ${albArn} \
            --query "TargetGroups[?contains(TargetGroupName, '${config.blueTargetGroupName}')].TargetGroupArn | [0]" \
            --output text
        """,
        returnStdout: true
    ).trim()

    if (!blueTGArn || blueTGArn == 'None') {
        error "❌ Could not find Blue Target Group ARN with name containing '${config.blueTargetGroupName}' under ALB '${config.albName}'"
    }
    echo "✅ Found Blue Target Group ARN: ${blueTGArn}"

    // 3. Get Blue Instance IP
    def blueInstanceIP = sh(
        script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${config.blueTag}" "Name=instance-state-name,Values=running" \
        --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
        """,
        returnStdout: true
    ).trim()

    if (!blueInstanceIP || blueInstanceIP == 'None') {
        error "❌ No running Blue instance found!"
    }
    echo "✅ Deploying to Blue instance: ${blueInstanceIP}"

    // 4. Copy App and Restart Service
    sshagent([env.SSH_KEY_ID]) {
        sh "scp -o StrictHostKeyChecking=no ${env.TF_WORKING_DIR}/modules/ec2/scripts/${env.APP_FILE} ec2-user@${blueInstanceIP}:/home/ec2-user/${env.APP_FILE}"
        sh "ssh -o StrictHostKeyChecking=no ec2-user@${blueInstanceIP} 'sudo systemctl restart flaskapp.service'"
    }
    env.BLUE_INSTANCE_IP = blueInstanceIP

    // 5. Health Check for Blue Instance
    echo "🔍 Monitoring health of Blue instance..."

    def blueInstanceId = sh(
        script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${config.blueTag}" "Name=instance-state-name,Values=running" \
        --query 'Reservations[0].Instances[0].InstanceId' --output text
        """,
        returnStdout: true
    ).trim()

    def healthStatus = ''
    def attempts = 0
    def maxAttempts = 30

    while (healthStatus != 'healthy' && attempts < maxAttempts) {
        sleep(time: 10, unit: 'SECONDS')
        healthStatus = sh(
            script: """
            aws elbv2 describe-target-health \
            --target-group-arn ${blueTGArn} \
            --targets Id=${blueInstanceId} \
            --query 'TargetHealthDescriptions[0].TargetHealth.State' \
            --output text
            """,
            returnStdout: true
        ).trim()
        attempts++
        echo "Health status check attempt ${attempts}: ${healthStatus}"
    }

    if (healthStatus != 'healthy') {
        error "❌ Blue instance failed to become healthy after ${maxAttempts} attempts!"
    }

    echo "✅ Blue instance is healthy!"
}


def switchTraffic(Map config) {
    echo "🔄 Fetching ALB ARN..."
    def albArn = sh(script: """
        aws elbv2 describe-load-balancers --names blue-green-alb \
        --query "LoadBalancers[0].LoadBalancerArn" --output text
    """, returnStdout: true).trim()

    if (!albArn) {
        error "❌ Failed to retrieve ALB ARN!"
    }
    echo "✅ ALB ARN: ${albArn}"

    echo "🔄 Fetching Listener ARN..."
    def listenerArn = sh(script: """
        aws elbv2 describe-listeners --load-balancer-arn ${albArn} \
        --query "Listeners[0].ListenerArn" --output text
    """, returnStdout: true).trim()

    if (!listenerArn) {
        error "❌ Listener ARN not found!"
    }
    echo "✅ Listener ARN: ${listenerArn}"

    echo "🔄 Fetching Blue Target Group ARN..."
    def blueTgArn = sh(script: """
        aws elbv2 describe-target-groups --names blue-tg \
        --query "TargetGroups[0].TargetGroupArn" --output text
    """, returnStdout: true).trim()

    if (!blueTgArn) {
        error "❌ Blue Target Group ARN not found!"
    }
    echo "✅ Blue TG ARN: ${blueTgArn}"

    echo "🔄 Fetching Green Target Group ARN..."
    def greenTgArn = sh(script: """
        aws elbv2 describe-target-groups --names green-tg \
        --query "TargetGroups[0].TargetGroupArn" --output text
    """, returnStdout: true).trim()

    if (!greenTgArn) {
        error "❌ Green Target Group ARN not found!"
    }
    echo "✅ Green TG ARN: ${greenTgArn}"

    echo "🔍 Checking for existing priority 10 rules..."
    def ruleArn = sh(script: """
        aws elbv2 describe-rules --listener-arn '${listenerArn}' \
        --query "Rules[?Priority=='10'].RuleArn | [0]" --output text
    """, returnStdout: true).trim()

    if (ruleArn && ruleArn != "None") {
        echo "🔄 Deleting existing rule with Priority 10..."
        sh "aws elbv2 delete-rule --rule-arn '${ruleArn}'"
        echo "✅ Deleted rule ${ruleArn}"
    } else {
        echo "ℹ️ No existing rule at priority 10"
    }

    echo "🔁 Switching traffic to BLUE..."
    sh """
        aws elbv2 modify-listener --listener-arn ${listenerArn} \
        --default-actions Type=forward,TargetGroupArn=${blueTgArn}
    """

    def currentTargetArn = sh(script: """
        aws elbv2 describe-listeners --listener-arns ${listenerArn} \
        --query "Listeners[0].DefaultActions[0].TargetGroupArn" --output text
    """, returnStdout: true).trim()

    if (currentTargetArn != blueTgArn) {
        error "❌ Verification failed! Listener not pointing to BLUE TG."
    }

    echo "✅✅✅ Traffic successfully routed to BLUE TG!"
}

def tagSwapInstances(Map config) {
    echo "🌐 Discovering AWS resources..."

    def instances = sh(script: """
        aws ec2 describe-instances \
            --filters "Name=tag:Name,Values=${config.blueTag},${config.greenTag}" \
                    "Name=instance-state-name,Values=running" \
            --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value | [0]]" \
            --output json
    """, returnStdout: true).trim()

    def instancesJson = readJSON text: instances
    def blueInstance = null
    def greenInstance = null

    for (instance in instancesJson) {
        if (instance[1] == config.blueTag) {
            blueInstance = instance[0]
        } else if (instance[1] == config.greenTag) {
            greenInstance = instance[0]
        }
    }

    if (!blueInstance || !greenInstance) {
        error "❌ Could not find both Blue and Green running instances. Found:\n${instancesJson}"
    }

    echo "✔️ Found instances - Blue: ${blueInstance}, Green: ${greenInstance}"
    echo "🔄 Performing atomic tag swap..."

    sh """
        #!/bin/bash
        set -euo pipefail

        BLUE_INSTANCE="${blueInstance}"
        GREEN_INSTANCE="${greenInstance}"
        BLUE_TAG="${config.blueTag}"
        GREEN_TAG="${config.greenTag}"

        echo "➡️ Swapping tags:"
        echo "- \$BLUE_INSTANCE will become \$GREEN_TAG"
        echo "- \$GREEN_INSTANCE will become \$BLUE_TAG"

        # Swap the Name tags
        aws ec2 create-tags --resources "\$BLUE_INSTANCE" --tags Key=Name,Value="\$GREEN_TAG"
        aws ec2 create-tags --resources "\$GREEN_INSTANCE" --tags Key=Name,Value="\$BLUE_TAG"

        # Verify the tag swap
        new_blue_tag=\$(aws ec2 describe-tags --filters "Name=resource-id,Values=\$BLUE_INSTANCE" "Name=key,Values=Name" --query "Tags[0].Value" --output text)
        new_green_tag=\$(aws ec2 describe-tags --filters "Name=resource-id,Values=\$GREEN_INSTANCE" "Name=key,Values=Name" --query "Tags[0].Value" --output text)

        echo "🧪 Verifying tag swap:"
        echo "- \$BLUE_INSTANCE: \$new_blue_tag"
        echo "- \$GREEN_INSTANCE: \$new_green_tag"

        if [[ "\$new_blue_tag" != "\$GREEN_TAG" || "\$new_green_tag" != "\$BLUE_TAG" ]]; then
            echo "❌ Tag verification failed!"
            exit 1
        fi
    """

    echo "✅ Deployment Complete!"
    echo "====================="
    echo "Instance Tags:"
    echo "- ${blueInstance} is now '${config.greenTag}'"
    echo "- ${greenInstance} is now '${config.blueTag}'"
}
