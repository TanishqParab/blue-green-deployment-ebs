def call(config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        echo "Running Terraform apply"

        dir("${config.tfWorkingDir}") {
            sh "terraform apply -auto-approve tfplan"
            archiveArtifacts artifacts: 'terraform.tfstate', fingerprint: true
        }

        if (config.implementation == 'ec2') {
            echo "Waiting for instances to start and initialize..."
            sleep(90)  // Allow time for user_data scripts to complete

            echo "Checking instance states..."
            sh """
            aws ec2 describe-instances \\
            --filters "Name=tag:Environment,Values=Blue-Green" \\
            --query 'Reservations[*].Instances[*].[InstanceId, State.Name]' \\
            --output table
            """

            def validApps = ["app1", "app2", "app3"]
            def appName = config.appName?.trim()?.toLowerCase()

            if (!appName) {
                echo "No appName provided, deploying all apps: ${validApps.join(', ')}"
                validApps.each { app ->
                    deployApp(app, config)
                }
            } else if (!validApps.contains(appName)) {
                error "Invalid appName '${appName}'. Must be one of: ${validApps.join(', ')}"
            } else {
                echo "Deploying single app: ${appName}"
                deployApp(appName, config)
            }
        } else if (config.implementation == 'ecs') {
            // ECS logic unchanged
            echo "Waiting for ECS services to stabilize..."
            sleep(60)

            def cluster = sh(
                script: "aws ecs list-clusters --query 'clusterArns[0]' --output text | awk -F'/' '{print \$2}'",
                returnStdout: true
            ).trim()

            sh """
            aws ecs describe-services --cluster ${cluster} --services blue-service --query 'services[0].{Status:status,DesiredCount:desiredCount,RunningCount:runningCount}' --output table
            """

            def albDns = sh(
                script: "aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].DNSName' --output text",
                returnStdout: true
            ).trim()

            echo "Application is accessible at: http://${albDns}"

            sh """
            sleep 30
            curl -f http://${albDns}/health || echo "Health check failed but continuing"
            """
        }
    }
}

def deployApp(String appName, Map config) {
    echo "Starting deployment for app: ${appName}"

    def blueTag = "${appName}-blue-instance"
    def greenTag = "${appName}-green-instance"

    def blueInstanceIP = getInstancePublicIp(blueTag)
    def greenInstanceIP = getInstancePublicIp(greenTag)

    if (!blueInstanceIP || blueInstanceIP == "None") {
        error "Blue instance IP not found or invalid for ${appName}!"
    }
    if (!greenInstanceIP || greenInstanceIP == "None") {
        error "Green instance IP not found or invalid for ${appName}!"
    }

    echo "Blue Instance IP for ${appName}: ${blueInstanceIP}"
    echo "Green Instance IP for ${appName}: ${greenInstanceIP}"

    def appFiles = []
    def destFiles = []

    switch(appName) {
        case "app1":
            appFiles = ["app_1.py"]
            destFiles = ["app_app1.py"]
            break
        case "app2":
            appFiles = ["app_2.py"]
            destFiles = ["app_app2.py"]
            break
        case "app3":
            appFiles = ["app_3.py"]
            destFiles = ["app_app3.py"]
            break
        default:
            appFiles = ["app.py"]
            destFiles = ["app_${appName}.py"]
    }

    def appPath = "${config.tfWorkingDir}/modules/ec2/scripts"

    def deployToInstance = { instanceIP ->
        sshagent([config.sshKeyId]) {
            echo "Deploying ${appName} to instance ${instanceIP}"
            sh "scp -o StrictHostKeyChecking=no ${appPath}/setup_flask_service.py ec2-user@${instanceIP}:/home/ec2-user/setup_flask_service.py"

            for (int i = 0; i < appFiles.size(); i++) {
                sh "scp -o StrictHostKeyChecking=no ${appPath}/${appFiles[i]} ec2-user@${instanceIP}:/home/ec2-user/${destFiles[i]}"
            }

            sh "ssh -o StrictHostKeyChecking=no ec2-user@${instanceIP} 'chmod +x /home/ec2-user/setup_flask_service.py && sudo python3 /home/ec2-user/setup_flask_service.py ${appName}'"
        }
    }

    parallel(
        Blue: {
            deployToInstance(blueInstanceIP)
        },
        Green: {
            deployToInstance(greenInstanceIP)
        }
    )

    // Health check both instances
    [blueInstanceIP, greenInstanceIP].each { ip ->
        echo "Checking health for instance ${ip} (app: ${appName})"
        try {
            sh "curl -m 10 -f http://${ip}/health"
            echo "Instance ${ip} for ${appName} is healthy."
        } catch (Exception e) {
            echo "⚠️ Warning: Health check failed for ${ip} (app: ${appName}): ${e.message}"
            echo "The instance may still be initializing. Try accessing it manually in a few minutes."
        }
    }

    echo "Deployment completed for app: ${appName}"
}

def getInstancePublicIp(String tagName) {
    return sh(
        script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${tagName}" "Name=instance-state-name,Values=running" \\
        --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
        """,
        returnStdout: true
    ).trim()
}
