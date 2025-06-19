// vars/ec2Utils.groovy

def registerInstancesToTargetGroups(Map config) {
    if (config.implementation != 'ec2' || params.MANUAL_BUILD == 'DESTROY') {
        echo "⚠️ Skipping EC2 registration as conditions not met."
        return
    }

    echo "📥 Fetching Target Group ARNs from AWS..."
    
    // Get app name from config or default to empty string (for backward compatibility)
    def appName = config.appName ?: ""
    def blueTgName = appName ? "blue-tg-${appName}" : "blue-tg"
    def greenTgName = appName ? "green-tg-${appName}" : "green-tg"
    
    // Use custom tag format if provided, otherwise use default format
    def blueInstanceTag = config.blueTag ?: (appName ? "${appName}-blue-instance" : "Blue-Instance")
    def greenInstanceTag = config.greenTag ?: (appName ? "${appName}-green-instance" : "Green-Instance")
    
    echo "🔍 Using target groups: ${blueTgName} and ${greenTgName}"
    echo "🔍 Using instance tags: ${blueInstanceTag} and ${greenInstanceTag}"

    env.BLUE_TG_ARN = sh(
        script: """
        aws elbv2 describe-target-groups --names "${blueTgName}" --query 'TargetGroups[0].TargetGroupArn' --output text
        """,
        returnStdout: true
    ).trim()

    env.GREEN_TG_ARN = sh(
        script: """
        aws elbv2 describe-target-groups --names "${greenTgName}" --query 'TargetGroups[0].TargetGroupArn' --output text
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
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${blueInstanceTag}" "Name=instance-state-name,Values=running" \
        --query 'Reservations[0].Instances[0].InstanceId' --output text
        """,
        returnStdout: true
    ).trim()

    def greenInstanceId = sh(
        script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${greenInstanceTag}" "Name=instance-state-name,Values=running" \
        --query 'Reservations[0].Instances[0].InstanceId' --output text
        """,
        returnStdout: true
    ).trim()

    // Check if instances exist before proceeding
    if (!blueInstanceId || blueInstanceId == "None" || !greenInstanceId || greenInstanceId == "None") {
        echo "⚠️ One or both instances not found. Blue: ${blueInstanceId}, Green: ${greenInstanceId}"
        echo "⚠️ This is normal for the first deployment. Skipping registration."
        return
    }

    echo "✅ Blue Instance ID: ${blueInstanceId}"
    echo "✅ Green Instance ID: ${greenInstanceId}"

    echo "🔄 Deregistering old instances before re-registering..."
    try {
        sh """
        aws elbv2 deregister-targets --target-group-arn ${env.BLUE_TG_ARN} --targets Id=${greenInstanceId}
        aws elbv2 deregister-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${blueInstanceId}
        """
    } catch (Exception e) {
        echo "⚠️ Warning during deregistration: ${e.message}"
        echo "⚠️ Continuing with registration..."
    }
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

    def changedFiles = sh(script: "git diff --name-only HEAD~1 HEAD", returnStdout: true).trim()
    
    if (!changedFiles) {
        echo "No changes detected."
        env.EXECUTION_TYPE = 'SKIP'
        return
    }
    
    def fileList = changedFiles.split('\n')
    echo "📝 Changed files: ${fileList.join(', ')}"
    echo "🚀 Change(s) detected. Triggering deployment."
    
    // Check for app file changes
    def appChanges = []
    def infraChanges = false
    
    fileList.each { file ->
        if (file.endsWith("app.py")) {
            appChanges.add("default")
        } else if (file.endsWith("app_1.py")) {
            appChanges.add("app1")
        } else if (file.endsWith("app_2.py")) {
            appChanges.add("app2")
        } else if (file.endsWith("app_3.py")) {
            appChanges.add("app3")
        } else {
            // Any other file is considered an infra change
            infraChanges = true
        }
    }
    
    if (appChanges.size() > 0 && !infraChanges) {
        echo "🚀 Detected app changes: ${appChanges}, executing App Deploy."
        env.EXECUTION_TYPE = 'APP_DEPLOY'
        
        // If multiple app files changed, use the first one
        if (appChanges.size() > 1) {
            echo "⚠️ Multiple app files changed. Using the first one: ${appChanges[0]}"
        }
        
        // Set the APP_NAME environment variable based on the changed app file
        if (appChanges[0] == "default") {
            env.APP_NAME = ""
        } else {
            env.APP_NAME = appChanges[0]
        }
        
        echo "🔍 Setting APP_NAME to: ${env.APP_NAME ?: 'default'}"
    } else {
        echo "✅ Infra changes detected, running full deployment."
        env.EXECUTION_TYPE = 'FULL_DEPLOY'
    }
}



def fetchResources(Map config) {
    echo "🔍 Fetching Target Group ARNs..."
    
    // Get app name from config or default to empty string (for backward compatibility)
    def appName = config.appName ?: ""
    def blueTgName = appName ? "blue-tg-${appName}" : "blue-tg"
    def greenTgName = appName ? "green-tg-${appName}" : "green-tg"
    
    echo "🔍 Using target groups: ${blueTgName} and ${greenTgName}"

    env.BLUE_TG_ARN = sh(
        script: """
        aws elbv2 describe-target-groups --names "${blueTgName}" --query 'TargetGroups[0].TargetGroupArn' --output text
        """,
        returnStdout: true
    ).trim()

    env.GREEN_TG_ARN = sh(
        script: """
        aws elbv2 describe-target-groups --names "${greenTgName}" --query 'TargetGroups[0].TargetGroupArn' --output text
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
    
    // Get app name from config or default to empty string (for backward compatibility)
    def appName = config.appName ?: ""
    
    // Use custom tag format if provided, otherwise use default format
    def blueInstanceTag = config.blueTag ?: (appName ? "${appName}-blue-instance" : "Blue-Instance")
    def greenInstanceTag = config.greenTag ?: (appName ? "${appName}-green-instance" : "Green-Instance")
    
    echo "🔍 Using instance tags: ${blueInstanceTag} and ${greenInstanceTag}"

    def blueInstanceId = sh(
        script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${blueInstanceTag}" "Name=instance-state-name,Values=running" \\
        --query 'Reservations[0].Instances[0].InstanceId' --output text
        """,
        returnStdout: true
    ).trim()

    def greenInstanceId = sh(
        script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${greenInstanceTag}" "Name=instance-state-name,Values=running" \\
        --query 'Reservations[0].Instances[0].InstanceId' --output text
        """,
        returnStdout: true
    ).trim()

    // Check if instances exist before proceeding
    if (!blueInstanceId || blueInstanceId == "None" || !greenInstanceId || greenInstanceId == "None") {
        error "❌ Blue or Green instance not found! Check AWS console."
    }

    echo "✅ Blue Instance ID: ${blueInstanceId}"
    echo "✅ Green Instance ID: ${greenInstanceId}"

    echo "❌ Deregistering old instances before re-registering..."
    try {
        sh """
            aws elbv2 deregister-targets --target-group-arn ${env.BLUE_TG_ARN} --targets Id=${greenInstanceId}
            aws elbv2 deregister-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${blueInstanceId}
        """
    } catch (Exception e) {
        echo "⚠️ Warning during deregistration: ${e.message}"
        echo "⚠️ Continuing with registration..."
    }
    sleep(10) // Allow time for deregistration

    echo "✅ Registering instances to the correct target groups..."
    sh """
        aws elbv2 register-targets --target-group-arn ${env.BLUE_TG_ARN} --targets Id=${blueInstanceId}
        aws elbv2 register-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${greenInstanceId}
    """

    echo "✅ Instances successfully registered to correct target groups!"
}


def deployToBlueInstance(Map config) {
    // CRITICAL: Ensure we have the latest code before deploying
    echo "🔄 Ensuring we have the latest code before deployment..."
    checkout scmGit(branches: [[name: env.REPO_BRANCH ?: 'main']], 
                  extensions: [], 
                  userRemoteConfigs: [[url: env.REPO_URL ?: 'https://github.com/TanishqParab/blue-green-deployment-ecs-test']])
    
    def appName = config.appName ?: ""
    def blueTargetGroupName = appName ? "blue-tg-${appName}" : (config.blueTargetGroupName ?: "blue-tg")
    def greenTargetGroupName = appName ? "green-tg-${appName}" : (config.greenTargetGroupName ?: "green-tg")
    def blueTag = appName ? "${appName}-blue-instance" : (config.blueTag ?: "Blue-Instance")
    def greenTag = appName ? "${appName}-green-instance" : (config.greenTag ?: "Green-Instance")
    
    echo "🔍 Using blue target group name: ${blueTargetGroupName}"
    echo "🔍 Using green target group name: ${greenTargetGroupName}"
    echo "🔍 Using blue instance tag: ${blueTag}"
    echo "🔍 Using green instance tag: ${greenTag}"
    
    // 1. Get ALB ARN
    def albArn = sh(script: """
        aws elbv2 describe-load-balancers --names "${config.albName}" --query 'LoadBalancers[0].LoadBalancerArn' --output text
    """, returnStdout: true).trim()
    if (!albArn || albArn == 'None') error "❌ Could not find ALB ARN with name '${config.albName}'"
    echo "✅ Found ALB ARN: ${albArn}"
    
    // 2. Get Blue and Green Target Group ARNs
    def blueTGArn = sh(script: """
        aws elbv2 describe-target-groups --load-balancer-arn ${albArn} --query "TargetGroups[?contains(TargetGroupName, '${blueTargetGroupName}')].TargetGroupArn | [0]" --output text
    """, returnStdout: true).trim()
    if (!blueTGArn || blueTGArn == 'None') error "❌ Could not find Blue Target Group ARN"
    echo "✅ Found Blue Target Group ARN: ${blueTGArn}"
    
    def greenTGArn = sh(script: """
        aws elbv2 describe-target-groups --load-balancer-arn ${albArn} --query "TargetGroups[?contains(TargetGroupName, '${greenTargetGroupName}')].TargetGroupArn | [0]" --output text
    """, returnStdout: true).trim()
    if (!greenTGArn || greenTGArn == 'None') error "❌ Could not find Green Target Group ARN"
    echo "✅ Found Green Target Group ARN: ${greenTGArn}"
    
    // 3. Get instance IDs and IPs
    def blueInstanceId = sh(script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${blueTag}" "Name=instance-state-name,Values=running" --query 'Reservations[0].Instances[0].InstanceId' --output text
    """, returnStdout: true).trim()
    def blueInstanceIP = sh(script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${blueTag}" "Name=instance-state-name,Values=running" --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
    """, returnStdout: true).trim()
    
    def greenInstanceId = sh(script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${greenTag}" "Name=instance-state-name,Values=running" --query 'Reservations[0].Instances[0].InstanceId' --output text
    """, returnStdout: true).trim()
    def greenInstanceIP = sh(script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${greenTag}" "Name=instance-state-name,Values=running" --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
    """, returnStdout: true).trim()
    
    if (!blueInstanceId || blueInstanceId == 'None' || !blueInstanceIP || blueInstanceIP == 'None') {
        error "❌ No running Blue instance found!"
    }
    if (!greenInstanceId || greenInstanceId == 'None' || !greenInstanceIP || greenInstanceIP == 'None') {
        error "❌ No running Green instance found!"
    }
    
    // 4. Determine active target group by checking which has healthy targets
    def blueHealth = sh(script: """
        aws elbv2 describe-target-health --target-group-arn ${blueTGArn} --query 'TargetHealthDescriptions[0].TargetHealth.State' --output text
    """, returnStdout: true).trim()
    def greenHealth = sh(script: """
        aws elbv2 describe-target-health --target-group-arn ${greenTGArn} --query 'TargetHealthDescriptions[0].TargetHealth.State' --output text
    """, returnStdout: true).trim()
    
    echo "Blue target group health: ${blueHealth}"
    echo "Green target group health: ${greenHealth}"
    
    def idleInstanceIP
    def idleInstanceId
    def idleTag
    def idleTGArn
    
    if (blueHealth == 'healthy') {
        echo "Blue target group is active; deploying to Green (idle) instance."
        idleInstanceIP = greenInstanceIP
        idleInstanceId = greenInstanceId
        idleTag = greenTag
        idleTGArn = greenTGArn
    } else if (greenHealth == 'healthy') {
        echo "Green target group is active; deploying to Blue (idle) instance."
        idleInstanceIP = blueInstanceIP
        idleInstanceId = blueInstanceId
        idleTag = blueTag
        idleTGArn = blueTGArn
    } else {
        error "❌ Neither Blue nor Green target groups have healthy targets!"
    }
    
    // 5. Determine the correct app file based on app name (matching your terraform apply naming)
    def sourceFile = ""
    def destFile = ""
    
    if (!appName || appName == "") {
        sourceFile = "app.py"
        destFile = "app_default.py"
    } else if (appName == "app1") {
        sourceFile = "app_1.py"
        destFile = "app_app1.py"
    } else if (appName == "app2") {
        sourceFile = "app_2.py"
        destFile = "app_app2.py"
    } else if (appName == "app3") {
        sourceFile = "app_3.py"
        destFile = "app_app3.py"
    } else {
        sourceFile = "app.py"
        destFile = "app_${appName}.py"
    }
    
    // 6. Clean up old app files on idle instance before copying new files
    sshagent([env.SSH_KEY_ID]) {
        echo "Cleaning up old app files on idle instance ${idleInstanceIP} (${idleTag})"
        sh "ssh -o StrictHostKeyChecking=no ec2-user@${idleInstanceIP} 'rm -f /home/ec2-user/app_*.py /home/ec2-user/app_app*.py'"
        
        echo "Deploying app to idle instance ${idleInstanceIP} (${idleTag})"
        sh """
            scp -o StrictHostKeyChecking=no ${config.appPath ?: "${env.TF_WORKING_DIR}/modules/ec2/scripts"}/${sourceFile} ec2-user@${idleInstanceIP}:/home/ec2-user/${destFile}
            scp -o StrictHostKeyChecking=no ${config.appPath ?: "${env.TF_WORKING_DIR}/modules/ec2/scripts"}/setup_flask_service.py ec2-user@${idleInstanceIP}:/home/ec2-user/setup_flask_service.py
            ssh -o StrictHostKeyChecking=no ec2-user@${idleInstanceIP} 'chmod +x /home/ec2-user/setup_flask_service.py && sudo python3 /home/ec2-user/setup_flask_service.py ${appName}'
        """
    }
    
    // 7. Health check the idle instance
    echo "🔍 Monitoring health of idle instance..."
    def healthStatus = ''
    def attempts = 0
    def maxAttempts = 30
    
    while (healthStatus != 'healthy' && attempts < maxAttempts) {
        sleep(time: 10, unit: 'SECONDS')
        healthStatus = sh(
            script: """
            aws elbv2 describe-target-health \\
            --target-group-arn ${idleTGArn} \\
            --targets Id=${idleInstanceId} \\
            --query 'TargetHealthDescriptions[0].TargetHealth.State' \\
            --output text
            """,
            returnStdout: true
        ).trim()
        attempts++
        echo "Health status check attempt ${attempts}: ${healthStatus}"
    }
    
    if (healthStatus != 'healthy') {
        error "❌ Idle instance failed to become healthy after ${maxAttempts} attempts!"
    }
    
    echo "✅ Idle instance is healthy and ready for traffic switch."
    
    // Note: Traffic switch logic should be handled separately after this deployment.
}





def switchTraffic(Map config) {
    // Get app name from config or default to empty string (for backward compatibility)
    def appName = config.appName ?: ""
    def albName = config.albName ?: "blue-green-alb"
    def blueTgName = appName ? "blue-tg-${appName}" : "blue-tg"
    def greenTgName = appName ? "green-tg-${appName}" : "green-tg"
    
    echo "🔍 Using ALB name: ${albName}"
    echo "🔍 Using target groups: ${blueTgName} and ${greenTgName}"
    
    try {
        echo "🔄 Fetching ALB ARN..."
        def albArn = sh(script: """
            aws elbv2 describe-load-balancers --names ${albName} \\
            --query "LoadBalancers[0].LoadBalancerArn" --output text
        """, returnStdout: true).trim()

        if (!albArn) {
            error "❌ Failed to retrieve ALB ARN!"
        }
        echo "✅ ALB ARN: ${albArn}"

        echo "🔄 Fetching Listener ARN..."
        def listenerArn = sh(script: """
            aws elbv2 describe-listeners --load-balancer-arn ${albArn} \\
            --query "Listeners[0].ListenerArn" --output text
        """, returnStdout: true).trim()

        if (!listenerArn) {
            error "❌ Listener ARN not found!"
        }
        echo "✅ Listener ARN: ${listenerArn}"

        echo "🔄 Fetching Blue Target Group ARN..."
        def blueTgArn = sh(script: """
            aws elbv2 describe-target-groups --names ${blueTgName} \\
            --query "TargetGroups[0].TargetGroupArn" --output text
        """, returnStdout: true).trim()

        if (!blueTgArn) {
            error "❌ Blue Target Group ARN not found!"
        }
        echo "✅ Blue TG ARN: ${blueTgArn}"

        echo "🔄 Fetching Green Target Group ARN..."
        def greenTgArn = sh(script: """
            aws elbv2 describe-target-groups --names ${greenTgName} \\
            --query "TargetGroups[0].TargetGroupArn" --output text
        """, returnStdout: true).trim()

        if (!greenTgArn) {
            error "❌ Green Target Group ARN not found!"
        }
        echo "✅ Green TG ARN: ${greenTgArn}"

        // Determine which target group to route traffic to (default to BLUE if not specified)
        def targetEnv = config.targetEnv?.toUpperCase() ?: "BLUE"
        def targetTgArn = (targetEnv == "BLUE") ? blueTgArn : greenTgArn
        
        echo "🔍 Checking for existing priority 10 rules..."
        def ruleArn = sh(script: """
            aws elbv2 describe-rules --listener-arn '${listenerArn}' \\
            --query "Rules[?Priority=='10'].RuleArn | [0]" --output text
        """, returnStdout: true).trim()

        if (ruleArn && ruleArn != "None") {
            echo "🔄 Deleting existing rule with Priority 10..."
            sh "aws elbv2 delete-rule --rule-arn '${ruleArn}'"
            echo "✅ Deleted rule ${ruleArn}"
        } else {
            echo "ℹ️ No existing rule at priority 10"
        }

        echo "🔁 Switching traffic to ${targetEnv}..."
        sh """
            aws elbv2 modify-listener --listener-arn ${listenerArn} \\
            --default-actions Type=forward,TargetGroupArn=${targetTgArn}
        """

        def currentTargetArn = sh(script: """
            aws elbv2 describe-listeners --listener-arns ${listenerArn} \\
            --query "Listeners[0].DefaultActions[0].TargetGroupArn" --output text
        """, returnStdout: true).trim()

        if (currentTargetArn != targetTgArn) {
            error "❌ Verification failed! Listener not pointing to ${targetEnv} TG."
        }

        echo "✅✅✅ Traffic successfully routed to ${targetEnv} TG!"
    } catch (Exception e) {
        echo "⚠️ Error switching traffic: ${e.message}"
        throw e
    }
}


def tagSwapInstances(Map config) {
    echo "🌐 Discovering AWS resources..."
    
    // Get app name from config or default to empty string (for backward compatibility)
    def appName = config.appName ?: ""
    def blueTag = appName ? "${appName}-blue-instance" : (config.blueTag ?: "Blue-Instance")
    def greenTag = appName ? "${appName}-green-instance" : (config.greenTag ?: "Green-Instance")
    
    echo "🔍 Using instance tags: ${blueTag} and ${greenTag}"

    def instances = sh(script: """
        aws ec2 describe-instances \\
            --filters "Name=tag:Name,Values=${blueTag},${greenTag}" \\
                    "Name=instance-state-name,Values=running" \\
            --query "Reservations[].Instances[].[InstanceId,Tags[?Key=='Name'].Value | [0]]" \\
            --output json
    """, returnStdout: true).trim()

    def instancesJson = readJSON text: instances
    def blueInstance = null
    def greenInstance = null

    for (instance in instancesJson) {
        if (instance[1] == blueTag) {
            blueInstance = instance[0]
        } else if (instance[1] == greenTag) {
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
        BLUE_TAG="${blueTag}"
        GREEN_TAG="${greenTag}"

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
    echo "- ${blueInstance} is now '${greenTag}'"
    echo "- ${greenInstance} is now '${blueTag}'"
}
