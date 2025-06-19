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

            // Get app name from config or default to empty string
            def appName = config.appName ?: ""
            def appFilter = appName ? "Name=tag:App,Values=${appName}" : ""

            def instances = sh(
                script: """
                aws ec2 describe-instances \\
                --filters "Name=tag:Environment,Values=Blue-Green" ${appFilter} "Name=instance-state-name,Values=running" \\
                --query 'Reservations[*].Instances[*].PublicIpAddress' \\
                --output text
                """,
                returnStdout: true
            ).trim()

            if (!instances) {
                error "No running instances found! Check AWS console and tagging."
            }

            def instanceList = instances.split("\n")

            // Construct blue and green instance tags based on appName
            def blueTag = appName ? "${appName}-blue-instance" : "Blue-Instance"
            def greenTag = appName ? "${appName}-green-instance" : "Green-Instance"

            // Get blue and green instance IPs
            def blueInstanceIP = sh(
                script: """
                aws ec2 describe-instances --filters "Name=tag:Name,Values=${blueTag}" "Name=instance-state-name,Values=running" \\
                --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
                """,
                returnStdout: true
            ).trim()

            def greenInstanceIP = sh(
                script: """
                aws ec2 describe-instances --filters "Name=tag:Name,Values=${greenTag}" "Name=instance-state-name,Values=running" \\
                --query 'Reservations[0].Instances[0].PublicIpAddress' --output text
                """,
                returnStdout: true
            ).trim()

            // Determine app files and destination names with latest naming conventions
            def appFiles = []
            def destFiles = []

            if (!appName || appName == "") {
                // Copy all app files with correct destination names
                appFiles = ["app_1.py", "app_2.py", "app_3.py"]
                destFiles = ["app_app1.py", "app_app2.py", "app_app3.py"]
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
                // For any other appName, fallback to app.py naming
                appFiles = ["app.py"]
                destFiles = ["app_${appName}.py"]
            }

            def appPath = "${config.tfWorkingDir}/modules/ec2/scripts"

            // Helper closure to deploy to an instance
            def deployToInstance = { instanceIP ->
                sshagent([config.sshKeyId]) {
                    echo "Deploying to instance ${instanceIP}"

                    // Copy setup script first
                    sh "scp -o StrictHostKeyChecking=no ${appPath}/setup_flask_service.py ec2-user@${instanceIP}:/home/ec2-user/setup_flask_service.py"

                    // Copy each app file with correct destination name
                    for (int i = 0; i < appFiles.size(); i++) {
                        sh "scp -o StrictHostKeyChecking=no ${appPath}/${appFiles[i]} ec2-user@${instanceIP}:/home/ec2-user/${destFiles[i]}"
                    }

                    // Run setup script to stop old app and start new one
                    sh "ssh -o StrictHostKeyChecking=no ec2-user@${instanceIP} 'chmod +x /home/ec2-user/setup_flask_service.py && sudo python3 /home/ec2-user/setup_flask_service.py ${appName}'"
                }
            }

            // Deploy in parallel to Blue and Green instances
            parallel(
                Blue: {
                    if (blueInstanceIP && blueInstanceIP != "None") {
                        deployToInstance(blueInstanceIP)
                    } else {
                        error "Blue instance IP not found or invalid!"
                    }
                },
                Green: {
                    if (greenInstanceIP && greenInstanceIP != "None") {
                        deployToInstance(greenInstanceIP)
                    } else {
                        error "Green instance IP not found or invalid!"
                    }
                }
            )

            // Health check all instances in the list
            instanceList.each { instanceIP ->
                echo "Checking health for instance ${instanceIP}"
                try {
                    sh "curl -m 10 -f http://${instanceIP}/health"
                    echo "Instance ${instanceIP} is healthy."
                } catch (Exception e) {
                    echo "⚠️ Warning: Health check failed for ${instanceIP}: ${e.message}"
                    echo "The instance may still be initializing. Try accessing it manually in a few minutes."
                }
            }
        } else if (config.implementation == 'ecs') {
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
