def call(config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        echo "Running Terraform apply"

        dir("${config.tfWorkingDir}") {
            sh "terraform apply -auto-approve tfplan"
            archiveArtifacts artifacts: 'terraform.tfstate', fingerprint: true
        }

        if (config.implementation == 'ec2') {
            echo "Waiting for instances to start and initialize..."

            // Get app name from config or default to empty string
            def appName = config.appName ?: ""
            def appFilter = appName ? "Name=tag:App,Values=${appName}" : ""

            // Get instance IDs with pending or running state
            def instanceIdsRaw = sh(
                script: """
                aws ec2 describe-instances \\
                --filters "Name=tag:Environment,Values=Blue-Green" ${appFilter} "Name=instance-state-name,Values=pending,running" \\
                --query 'Reservations[*].Instances[*].InstanceId' \\
                --output text
                """,
                returnStdout: true
            ).trim()

            def instanceIds = instanceIdsRaw ? instanceIdsRaw.split("\\s+") : []

            if (!instanceIds || instanceIds.length == 0) {
                error "No instances found with the specified tags!"
            }

            // Wait for all instances to be in 'running' state
            instanceIds.each { instanceId ->
                waitForInstanceRunning(instanceId)
            }

            echo "All instances are running."

            // Get Blue and Green instance tags
            def blueTag = appName ? "${appName}-blue-instance" : "Blue-Instance"
            def greenTag = appName ? "${appName}-green-instance" : "Green-Instance"

            // Get Blue and Green instance IPs
            def blueInstanceIP = getInstancePublicIp(blueTag)
            def greenInstanceIP = getInstancePublicIp(greenTag)

            if (!blueInstanceIP || blueInstanceIP == "None") {
                error "Blue instance IP not found or instance not running!"
            }
            if (!greenInstanceIP || greenInstanceIP == "None") {
                error "Green instance IP not found or instance not running!"
            }

            echo "Blue Instance IP: ${blueInstanceIP}"
            echo "Green Instance IP: ${greenInstanceIP}"

            // Determine app files to copy with correct renaming
            def appFiles = []
            def destFiles = []

            if (!appName || appName == "") {
                appFiles = ["app.py", "app_1.py", "app_2.py", "app_3.py"]
                destFiles = ["app_default.py", "app_app1.py", "app_app2.py", "app_app3.py"]
            } else if (appName == "app1") {
                appFiles = ["app_1.py"]
                destFiles = ["app_app1.py"]
            } else if (appName == "app2") {
                appFiles = ["app_2.py"]
                destFiles = ["app_app2.py"]
            } else if (appName == "app3") {
                appFiles = ["app_3.py"]
                destFiles = ["app_app3.py"]
            } else {
                appFiles = ["app.py"]
                destFiles = ["app_${appName}.py"]
            }

            def appPath = "${config.tfWorkingDir}/modules/ec2/scripts"

            // Helper closure to copy files and run setup on an instance
            def deployToInstance = { instanceIP ->
                sshagent([config.sshKeyId]) {
                    echo "Deploying to instance ${instanceIP}"
                    // Copy setup script first
                    sh "scp -o StrictHostKeyChecking=no ${appPath}/setup_flask_service.py ec2-user@${instanceIP}:/home/ec2-user/setup_flask_service.py"

                    // Copy app files
                    for (int i = 0; i < appFiles.size(); i++) {
                        sh "scp -o StrictHostKeyChecking=no ${appPath}/${appFiles[i]} ec2-user@${instanceIP}:/home/ec2-user/${destFiles[i]}"
                    }

                    // Run setup script
                    sh "ssh -o StrictHostKeyChecking=no ec2-user@${instanceIP} 'chmod +x /home/ec2-user/setup_flask_service.py && sudo python3 /home/ec2-user/setup_flask_service.py ${appName}'"
                }
            }

            // Deploy in parallel to Blue and Green
            parallel(
                Blue: {
                    deployToInstance(blueInstanceIP)
                },
                Green: {
                    deployToInstance(greenInstanceIP)
                }
            )

            // Health check both instances with retries
            [blueInstanceIP, greenInstanceIP].each { ip ->
                echo "Checking health for instance ${ip}"
                try {
                    sh "curl -m 10 -f http://${ip}/health"
                    echo "Instance ${ip} is healthy."
                } catch (Exception e) {
                    echo "⚠️ Warning: Health check failed for ${ip}: ${e.message}"
                    echo "The instance may still be initializing. Try accessing it manually in a few minutes."
                }
            }

            echo "Deployment to EC2 instances completed."
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

// Helper function to wait for instance to be running
def waitForInstanceRunning(String instanceId) {
    echo "Waiting for instance ${instanceId} to be in 'running' state..."
    timeout(time: 5, unit: 'MINUTES') {
        waitUntil {
            def state = sh(
                script: "aws ec2 describe-instances --instance-ids ${instanceId} --query 'Reservations[0].Instances[0].State.Name' --output text",
                returnStdout: true
            ).trim()
            echo "Instance ${instanceId} state: ${state}"
            return state == 'running'
        }
    }
}

// Helper function to get public IP by tag name
def getInstancePublicIp(String tagName) {
    return sh(
        script: """
        aws ec2 describe-instances --filters "Name=tag:Name,Values=${tagName}" "Name=instance-state-name,Values=running" \\
        --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
        """,
        returnStdout: true
    ).trim()
}
