// vars/ec2RollbackUtils.groovy

def fetchResources(Map config) {
    echo "🔄 Fetching EC2 ALB and target group resources..."

    def albArn = sh(script: """
        aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text
    """, returnStdout: true).trim()

    if (!albArn) {
        error "❌ Failed to retrieve ALB ARN! Check if the load balancer 'blue-green-alb' exists in AWS."
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
        aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text
    """, returnStdout: true).trim()

    env.GREEN_TG_ARN = sh(script: """
        aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text
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
}

def prepareRollback(Map config) {
    echo "🛠️ Creating rollback traffic rule..."
    sh """
        aws elbv2 create-rule \\
            --listener-arn ${env.LISTENER_ARN} \\
            --priority 10 \\
            --conditions Field=path-pattern,Values='/*' \\
            --actions Type=forward,TargetGroupArn=${env.GREEN_TG_ARN}
    """

    def targetHealthData = sh(script: """
        aws elbv2 describe-target-health \\
            --target-group-arn ${env.GREEN_TG_ARN} \\
            --query 'TargetHealthDescriptions[*].[Target.Id, TargetHealth.State]' \\
            --output text
    """, returnStdout: true).trim()

    echo "🔍 All target health data in green-tg:\n${targetHealthData}"

    def targetInstanceIds = targetHealthData.readLines().collect { it.split()[0] }
    def instanceIds = targetInstanceIds.join(' ')

    def instanceDetails = sh(script: '''
        aws ec2 describe-instances \\
            --instance-ids ''' + instanceIds + ''' \\
            --query "Reservations[*].Instances[*].[InstanceId, Tags[?Key=='Name']|[0].Value]" \\
            --output text
    ''', returnStdout: true).trim()

    echo "🔍 Fetched EC2 instance names:\n${instanceDetails}"

    def blueLine = instanceDetails.readLines().find { line ->
        def parts = line.split('\t')
        return parts.size() == 2 && parts[1].equalsIgnoreCase('blue-instance')
    }

    if (!blueLine) {
        error "❌ No instance with tag Name=blue-instance found in green-tg. Cannot proceed with rollback."
    }

    def (blueInstanceId, instanceName) = blueLine.split('\t')
    def healthState = targetHealthData.readLines().find { it.startsWith(blueInstanceId) }?.split()[1]

    if (!healthState) {
        error "❌ blue-instance is not currently registered in green-tg or health data is missing."
    }

    echo "✅ Found blue-instance (${blueInstanceId}) with health state: ${healthState}"
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
        }
    }

    if (!healthy) {
        error "❌ Rollback failed: Standby instance did not become healthy (Final state: ${healthState})"
    }
}

def executeEc2Rollback(Map config) {
    echo "✅✅✅ EC2 ROLLBACK COMPLETE: Traffic now routed to previous version (GREEN-TG)"
}




