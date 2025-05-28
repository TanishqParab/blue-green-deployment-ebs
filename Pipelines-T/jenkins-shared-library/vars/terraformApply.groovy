// vars/terraformApply.groovy

def call(config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        echo "Running Terraform apply"

        dir("${config.tfWorkingDir}") {
            sh "terraform apply -auto-approve tfplan"
            archiveArtifacts artifacts: 'terraform.tfstate', fingerprint: true
        }

        if (config.implementation == 'ec2') {
            echo "Waiting for instances to start..."
            sleep(60)

            echo "Checking instance states..."
            sh """
            aws ec2 describe-instances \\
            --filters "Name=tag:Environment,Values=Blue-Green" \\
            --query 'Reservations[*].Instances[*].[InstanceId, State.Name]' \\
            --output table
            """

            def instances = sh(
                script: """
                aws ec2 describe-instances \\
                --filters "Name=tag:Environment,Values=Blue-Green" "Name=instance-state-name,Values=running" \\
                --query 'Reservations[*].Instances[*].PublicIpAddress' \\
                --output text
                """,
                returnStdout: true
            ).trim()

            if (!instances) {
                error "No running instances found! Check AWS console and tagging."
            }

            def instanceList = instances.split("\n")
            instanceList.each { instance ->
                echo "Deploying to instance: ${instance}"
                sshagent([config.sshKeyId]) {
                    sh """
                    scp -o StrictHostKeyChecking=no ${config.tfWorkingDir}/modules/ec2/scripts/${config.appFile} ec2-user@${instance}:/home/ec2-user/${config.appFile}
                    scp -o StrictHostKeyChecking=no ${config.tfWorkingDir}/modules/ec2/scripts/setup_flask_service.py ec2-user@${instance}:/home/ec2-user/setup_flask_service.py
                    ssh ec2-user@${instance} 'chmod +x /home/ec2-user/setup_flask_service.py && sudo python3 /home/ec2-user/setup_flask_service.py'
                    """
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
