def fetchResources(Map config) {
    echo "🔄 Fetching EC2 ALB and target group resources..."

    def appName = config.appName
    if (!appName) {
        error "❌ APP_NAME not provided. Rollback requires a specific application name like 'app1'."
    }

    def albName = config.albName ?: "blue-green-alb"
    def blueTgName = "blue-tg-${appName}"
    def greenTgName = "green-tg-${appName}"

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
    echo "🛠️ Initiating EC2 rollback process..."

    def appName = config.appName
    if (!appName) {
        error "❌ APP_NAME not provided. Rollback requires a specific application name like 'app1'."
    }

    def blueInstanceTag = "${appName}-blue-instance"
    def rollbackPathWildcard = "/${appName}*"

    echo "🔍 Target blue instance tag: ${blueInstanceTag}"
    echo "🔍 Using wildcard path pattern: ${rollbackPathWildcard}"

    // Modify existing wildcard rule to point to GREEN_TG
    try {
        def ruleArn = sh(script: """
            aws elbv2 describe-rules \
              --listener-arn ${env.LISTENER_ARN} \
              --query "Rules[?Conditions[?Field=='path-pattern' && contains(Values[0], '${rollbackPathWildcard}')]].RuleArn" \
              --output text
        """, returnStdout: true).trim()

        if (ruleArn && ruleArn != "None") {
            echo "✅ Found existing wildcard rule (${rollbackPathWildcard}): ${ruleArn}"
            sh """
                aws elbv2 modify-rule \
                  --rule-arn ${ruleArn} \
                  --actions Type=forward,TargetGroupArn=${env.GREEN_TG_ARN}
            """
        } else {
            error "❌ No existing wildcard rule found for ${rollbackPathWildcard}. Cannot proceed with rollback."
        }
    } catch (Exception e) {
        echo "⚠️ Failed to update listener rule: ${e.message}. Continuing rollback..."
    }

    // Find healthy instances in GREEN TG
    def targetHealthData = sh(script: """
        aws elbv2 describe-target-health \
          --target-group-arn ${env.GREEN_TG_ARN} \
          --query 'TargetHealthDescriptions[*].[Target.Id, TargetHealth.State]' \
          --output text
    """, returnStdout: true).trim()

    def targetInstanceIds = targetHealthData.readLines().collect { it.split()[0] }
    if (targetInstanceIds.isEmpty()) {
        echo "⚠️ No healthy instances found in GREEN TG. Cannot rollback."
        return
    }

    def instanceIds = targetInstanceIds.join(' ')
    def instanceDetails = sh(script: """
        aws ec2 describe-instances \
          --instance-ids ${instanceIds} \
          --query "Reservations[*].Instances[*].[InstanceId, Tags[?Key=='Name']|[0].Value]" \
          --output text
    """, returnStdout: true).trim()

    def blueLine = instanceDetails.readLines().find { line ->
        def parts = line.split('\t')
        return parts.size() == 2 && parts[1].equalsIgnoreCase(blueInstanceTag)
    }

    if (!blueLine) {
        echo "⚠️ Could not find blue-tagged instance in GREEN TG. Cannot rollback."
        return
    }

    def (standbyInstanceId, _) = blueLine.split('\t')
    env.STANDBY_INSTANCE = standbyInstanceId

    // Wait until instance is healthy
    def healthState = ''
    def attempts = 0
    while (healthState != 'healthy' && attempts < 12) {
        sleep(time: 10, unit: 'SECONDS')
        healthState = sh(script: """
            aws elbv2 describe-target-health \
              --target-group-arn ${env.GREEN_TG_ARN} \
              --targets Id=${env.STANDBY_INSTANCE} \
              --query 'TargetHealthDescriptions[0].TargetHealth.State' \
              --output text
        """, returnStdout: true).trim()
        attempts++
        echo "Health check ${attempts}/12: ${healthState}"

        if (healthState == 'unused' && attempts > 3) {
            echo "⚠️ Re-registering instance to refresh health checks..."
            sh """
                aws elbv2 deregister-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${env.STANDBY_INSTANCE}
                sleep 10
                aws elbv2 register-targets --target-group-arn ${env.GREEN_TG_ARN} --targets Id=${env.STANDBY_INSTANCE}
                sleep 10
            """
        }
    }

    if (healthState != 'healthy') {
        echo "❌ Instance did not become healthy. Aborting rollback."
        return
    }

    def standbyIp = sh(script: """
        aws ec2 describe-instances --instance-ids ${env.STANDBY_INSTANCE} \
        --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
    """, returnStdout: true).trim()

    echo "🛠️ Rolling back app on ${blueInstanceTag} (${standbyIp})"

    // Upload and execute rollback script
    sshagent([env.SSH_KEY_ID]) {
        def tfDir = env.TF_WORKING_DIR ?: "blue-green-deployment-ecs-test/blue-green-deployment"
        def localScript = "${tfDir}/modules/ec2/scripts/setup_flask_service_switch.py"

        echo "📁 Checking rollback script: ${localScript}"
        if (!fileExists(localScript)) {
            error "❌ Local rollback script not found: ${localScript}"
        }

        echo "📤 Uploading script..."
        sh "scp -o StrictHostKeyChecking=no ${localScript} ec2-user@${standbyIp}:/home/ec2-user/setup_flask_service_switch.py"

        def remoteStatus = sh(script: """
            ssh -o StrictHostKeyChecking=no ec2-user@${standbyIp} 'test -f /home/ec2-user/setup_flask_service_switch.py && echo exists || echo missing'
        """, returnStdout: true).trim()

        if (remoteStatus != "exists") {
            error "❌ Remote rollback script missing after SCP."
        }

        echo "🔒 Making script executable..."
        sh "ssh -o StrictHostKeyChecking=no ec2-user@${standbyIp} 'chmod +x /home/ec2-user/setup_flask_service_switch.py'"
    }

    // Trigger rollback script
    sshagent([env.SSH_KEY_ID]) {
        sh "ssh -o StrictHostKeyChecking=no ec2-user@${standbyIp} 'sudo python3 /home/ec2-user/setup_flask_service_switch.py ${appName} rollback'"
    }

    echo "✅ Rollback completed for ${appName} on ${standbyIp}"
}

def executeEc2Rollback(Map config) {
    echo "✅✅✅ EC2 ROLLBACK COMPLETE: Traffic now routed to previous version (GREEN-TG)"
}
