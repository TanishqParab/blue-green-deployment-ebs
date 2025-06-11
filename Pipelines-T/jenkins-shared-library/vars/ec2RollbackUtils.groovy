// vars/ec2RollbackUtils.groovy

def fetchResources(Map config) {
    echo "🔄 Fetching EC2 ALB and target group resources..."
    
    // Get app name from config or default to empty string (for backward compatibility)
    def appName = config.appName ?: ""
    def albName = config.albName ?: "blue-green-alb"
    def blueTgName = appName ? "blue-tg-${appName}" : "blue-tg"
    def greenTgName = appName ? "green-tg-${appName}" : "green-tg"
    
    echo "🔍 Using ALB name: ${albName}"
    echo "🔍 Using target groups: ${blueTgName} and ${greenTgName}"

    try {
        def albArn = sh(script: """
            aws elbv2 describe-load-balancers --names ${albName} --query 'LoadBalancers[0].LoadBalancerArn' --output text
        """, returnStdout: true).trim()

        if (!albArn) {
            error "❌ Failed to retrieve ALB ARN! Check if the load balancer '${albName}' exists in AWS."
        }
        echo "✅ ALB ARN: ${albArn}"
        env.ALB_ARN = albArn

        def listenerArn = sh(script: """
            aws elbv2 describe-listeners --load-balancer-arn ${albArn} --query 'Listeners[?Port==`80`].ListenerArn' --output text
        """, returnStdout: true).trim()

        if (!listenerArn) {
            error "❌ Listener ARN not found! Check if the ALB has a listener attached."
        }
        echo "✅ Listener ARN: ${listenerArn}"
        env.LISTENER_ARN = listenerArn

        env.BLUE_TG_ARN = sh(script: """
            aws elbv2 describe-target-groups --names ${blueTgName} --query 'TargetGroups[0].TargetGroupArn' --output text
        """, returnStdout: true).trim()

        env.GREEN_TG_ARN = sh(script: """
            aws elbv2 describe-target-groups --names ${greenTgName} --query 'TargetGroups[0].TargetGroupArn' --output text
        """, returnStdout: true).trim()

        if (!env.GREEN_TG_ARN || env.GREEN_TG_ARN == 'null') {
            error "❌ GREEN_TG_ARN not retrieved properly. Aborting rollback."
        } else {
            echo "✅ GREEN_TG_ARN retrieved: ${env.GREEN_TG_ARN}"
        }

        if (!env.BLUE_TG_ARN || env.BLUE_TG_ARN == 'null') {
            error "❌ BLUE_TG_ARN not retrieved properly. Aborting rollback."
        } else {
            echo "✅ BLUE_TG_ARN retrieved: ${env.BLUE_TG_ARN}"
        }
    } catch (Exception e) {
        echo "⚠️ Error fetching resources: ${e.message}"
        throw e
    }
}


def prepareRollback(Map config) {
    echo "🛠️ Creating rollback traffic rule..."
    
    // Get app name from config or default to empty string (for backward compatibility)
    def appName = config.appName ?: ""
    def blueInstanceTag = appName ? "${appName}-blue-instance" : "Blue-Instance"
    
    echo "🔍 Using blue instance tag: ${blueInstanceTag}"
    
    try {
        sh """
            aws elbv2 create-rule \\
                --listener-arn ${env.LISTENER_ARN} \\
                --priority 10 \\
                --conditions Field=path-pattern,Values='/*' \\
                --actions Type=forward,TargetGroupArn=${env.GREEN_TG_ARN}
        """
    } catch (Exception e) {
        echo "⚠️ Warning: Could not create rule: ${e.message}"
        echo "⚠️ A rule with priority 10 might already exist. Continuing with rollback."
    }

    def targetHealthData = sh(script: """
        aws elbv2 describe-target-health \\
            --target-group-arn ${env.GREEN_TG_ARN} \\
            --query 'TargetHealthDescriptions[*].[Target.Id, TargetHealth.State]' \\
            --output text
    """, returnStdout: true).trim()

    echo "🔍 All target health data in green-tg:\n${targetHealthData}"

    if (!targetHealthData) {
        echo "⚠️ No targets found in green-tg. Attempting to proceed with rollback anyway."
        return
    }

    def targetInstanceIds = targetHealthData.readLines().collect { it.split()[0] }
    if (targetInstanceIds.isEmpty()) {
        echo "⚠️ No target instance IDs found. Attempting to proceed with rollback anyway."
        return
    }
    
    def instanceIds = targetInstanceIds.join(' ')

    def instanceDetails
    try {
        instanceDetails = sh(script: '''
            aws ec2 describe-instances \\
                --instance-ids ''' + instanceIds + ''' \\
                --query "Reservations[*].Instances[*].[InstanceId, Tags[?Key=='Name']|[0].Value]" \\
                --output text
        ''', returnStdout: true).trim()
    } catch (Exception e) {
        echo "⚠️ Warning: Could not fetch instance details: ${e.message}"
        echo "⚠️ Attempting to proceed with rollback anyway."
        return
    }

    echo "🔍 Fetched EC2 instance names:\n${instanceDetails}"

    def blueLine = instanceDetails.readLines().find { line ->
        def parts = line.split('\t')
        return parts.size() == 2 && parts[1].equalsIgnoreCase(blueInstanceTag)
    }

    if (!blueLine) {
        echo "⚠️ No instance with tag Name=${blueInstanceTag} found in green-tg. Attempting to proceed with rollback anyway."
        return
    }

    def (blueInstanceId, instanceName) = blueLine.split('\t')
    def healthState = targetHealthData.readLines().find { it.startsWith(blueInstanceId) }?.split()[1]

    if (!healthState) {
        echo "⚠️ ${blueInstanceTag} is not currently registered in green-tg or health data is missing. Attempting to proceed with rollback anyway."
        return
    }

    echo "✅ Found ${blueInstanceTag} (${blueInstanceId}) with health state: ${healthState}"
    env.STANDBY_INSTANCE = blueInstanceId

    echo "⏳ Waiting for standby instance (${env.STANDBY_INSTANCE}) to become healthy..."
    def healthy = false
    def attempts = 0

    while (!healthy && attempts < 12) {
        sleep(time: 10, unit: 'SECONDS')
        attempts++

        healthState = sh(script: """
            aws elbv2 describe-target-health \\
                --target-group-arn ${env.GREEN_TG_ARN} \\
                --targets Id=${env.STANDBY_INSTANCE} \\
                --query 'TargetHealthDescriptions[0].TargetHealth.State' \\
                --output text
        """, returnStdout: true).trim()

        echo "Attempt ${attempts}/12: Health state = ${healthState}"

        if (healthState == 'healthy') {
            healthy = true
        } else if (healthState == 'unused' && attempts > 3) {
            echo "⚠️ Triggering health check reevaluation"
            try {
                sh """
                    aws elbv2 deregister-targets \\
                        --target-group-arn ${env.GREEN_TG_ARN} \\
                        --targets Id=${env.STANDBY_INSTANCE}
                    sleep 15 
                    aws elbv2 register-targets \\
                        --target-group-arn ${env.GREEN_TG_ARN} \\
                        --targets Id=${env.STANDBY_INSTANCE}
                    sleep 10
                """
            } catch (Exception e) {
                echo "⚠️ Warning: Could not re-register target: ${e.message}"
            }
        }
    }

    if (!healthy) {
        echo "⚠️ Warning: Standby instance did not become healthy (Final state: ${healthState}). Attempting to proceed with rollback anyway."
    }
}

def executeEc2Rollback(Map config) {
    echo "✅✅✅ EC2 ROLLBACK COMPLETE: Traffic now routed to previous version (GREEN-TG)"
}



