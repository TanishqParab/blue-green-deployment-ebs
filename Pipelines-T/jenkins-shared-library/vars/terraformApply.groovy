// vars/terraformApply.groovy

def call(config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        echo "Running Terraform apply"

        dir("${config.tfWorkingDir}") {
            sh "terraform apply -auto-approve tfplan"
            archiveArtifacts artifacts: 'terraform.tfstate', fingerprint: true
        }

        if (config.implementation == 'ec2') {
            echo "Waiting for instances to start and initialize..."
            sleep(90)  // Increased wait time to allow user_data script to complete

            echo "Checking instance states..."
            sh """
            aws ec2 describe-instances \\
            --filters "Name=tag:Environment,Values=Blue-Green" \\
            --query 'Reservations[*].Instances[*].[InstanceId, State.Name]' \\
            --output table
            """

            // Get app name from config or default to empty string (for backward compatibility)
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
            
            // Get blue and green instance tags
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
            
            // Determine app files to copy with correct renaming
            def appFiles = []
            def destFiles = []
            
            if (!appName || appName == "") {
                // If no specific app, copy all app files
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
            
            // Copy app files and setup script to both instances
            def appPath = "${config.tfWorkingDir}/modules/ec2/scripts"
            
            if (blueInstanceIP && blueInstanceIP != "None") {
                echo "Copying app files to Blue instance: ${blueInstanceIP}"
                sshagent([config.sshKeyId]) {
                    // Copy setup script first
                    sh "scp -o StrictHostKeyChecking=no ${appPath}/setup_flask_service.py ec2-user@${blueInstanceIP}:/home/ec2-user/setup_flask_service.py"
                    
                    // Copy each app file with correct renaming
                    for (int i = 0; i < appFiles.size(); i++) {
                        sh "scp -o StrictHostKeyChecking=no ${appPath}/${appFiles[i]} ec2-user@${blueInstanceIP}:/home/ec2-user/${destFiles[i]}"
                    }
                    
                    // Run setup script for the specific app or default
                    sh "ssh -o StrictHostKeyChecking=no ec2-user@${blueInstanceIP} 'chmod +x /home/ec2-user/setup_flask_service.py && sudo python3 /home/ec2-user/setup_flask_service.py ${appName}'"
                }
            }
            
            if (greenInstanceIP && greenInstanceIP != "None") {
                echo "Copying app files to Green instance: ${greenInstanceIP}"
                sshagent([config.sshKeyId]) {
                    // Copy setup script first
                    sh "scp -o StrictHostKeyChecking=no ${appPath}/setup_flask_service.py ec2-user@${greenInstanceIP}:/home/ec2-user/setup_flask_service.py"
                    
                    // Copy each app file with correct renaming
                    for (int i = 0; i < appFiles.size(); i++) {
                        sh "scp -o StrictHostKeyChecking=no ${appPath}/${appFiles[i]} ec2-user@${greenInstanceIP}:/home/ec2-user/${destFiles[i]}"
                    }
                    
                    // Run setup script for the specific app or default
                    sh "ssh -o StrictHostKeyChecking=no ec2-user@${greenInstanceIP} 'chmod +x /home/ec2-user/setup_flask_service.py && sudo python3 /home/ec2-user/setup_flask_service.py ${appName}'"
                }
            }

            instanceList.each { instance ->
                echo "Instance ${instance} is now available at: http://${instance}/"
                
                // Verify the instance is responding (optional)
                try {
                    sh "curl -m 5 -f http://${instance}/health || echo 'Health check failed but continuing'"
                } catch (Exception e) {
                    echo "⚠️ Warning: Health check for ${instance} failed: ${e.message}"
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
