// vars/ecsInitialDeploymentUtils.groovy

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def deployToBlueService(Map config) {
    // Get app name from config or default to app_1
    def appName = config.appName ?: "app_1"
    def appSuffix = appName.replace("app_", "")
    
    echo "🚀 Deploying initial application ${appName} to Blue Service..."

    try {
        // Get ECR repository URI
        def ecrUri = sh(
            script: "aws ecr describe-repositories --repository-names ${config.ecrRepoName} --region ${config.awsRegion} --query 'repositories[0].repositoryUri' --output text",
            returnStdout: true
        ).trim()
        
        // Build and push Docker image for the specified app
        sh """
            aws ecr get-login-password --region ${config.awsRegion} | docker login --username AWS --password-stdin ${ecrUri}
            cd ${config.tfWorkingDir}/modules/ecs/scripts
            docker build -t ${config.ecrRepoName}:${appName} --build-arg APP_NAME=${appSuffix} .
            docker tag ${config.ecrRepoName}:${appName} ${ecrUri}:${appName}
            docker push ${ecrUri}:${appName}
            
            # Also tag as app-specific latest
            docker tag ${config.ecrRepoName}:${appName} ${ecrUri}:${appName}-latest
            docker push ${ecrUri}:${appName}-latest
        """
        
        // Get services JSON and find blue service
        def servicesJson = sh(
            script: "aws ecs list-services --cluster blue-green-cluster --region ${config.awsRegion} --output json",
            returnStdout: true
        ).trim()
        
        def serviceArns = initialDeploymentParseJson(servicesJson).serviceArns
        
        // Look for app-specific blue service first
        def blueServiceArn = serviceArns.find { it.toLowerCase().contains("blue-service-${appSuffix}") }
        
        // Fall back to default blue service if app-specific not found
        if (!blueServiceArn) {
            blueServiceArn = serviceArns.find { it.toLowerCase().contains("blue-service") }
        }
        
        if (!blueServiceArn) {
            error "❌ Could not find blue service in cluster blue-green-cluster"
        }
        
        def blueService = blueServiceArn.tokenize('/').last()
        echo "Found blue service: ${blueService}"
        
        // Get task definition ARN
        def taskDefArn = sh(
            script: """
            aws ecs describe-services --cluster blue-green-cluster --services "${blueService}" --region ${config.awsRegion} --query 'services[0].taskDefinition' --output text
            """,
            returnStdout: true
        ).trim()
        
        echo "Found task definition ARN: ${taskDefArn}"
        
        // Get task definition JSON
        def taskDefJsonText = sh(
            script: """
            aws ecs describe-task-definition --task-definition "${taskDefArn}" --region ${config.awsRegion} --query 'taskDefinition' --output json
            """,
            returnStdout: true
        ).trim()
        
        // Update task definition with new image
        def newTaskDefJson = initialDeploymentUpdateTaskDef(taskDefJsonText, "${ecrUri}:${appName}-latest")
        writeFile file: "initial-task-def-${appSuffix}.json", text: newTaskDefJson
        
        def newTaskDefArn = sh(
            script: "aws ecs register-task-definition --cli-input-json file://initial-task-def-${appSuffix}.json --region ${config.awsRegion} --query 'taskDefinition.taskDefinitionArn' --output text",
            returnStdout: true
        ).trim()
        
        echo "Registered new task definition: ${newTaskDefArn}"
        
        // Update service with new task definition
        sh """
        aws ecs update-service \\
            --cluster blue-green-cluster \\
            --service "${blueService}" \\
            --task-definition "${newTaskDefArn}" \\
            --desired-count 1 \\
            --force-new-deployment \\
            --region ${config.awsRegion}
        """
        
        // Look for app-specific target group
        def blueTgName = "blue-tg-${appSuffix}"
        def blueTgArn = sh(
            script: "aws elbv2 describe-target-groups --names ${blueTgName} --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || echo ''",
            returnStdout: true
        ).trim()
        
        // Fall back to default target group if app-specific not found
        if (!blueTgArn) {
            blueTgName = "blue-tg"
            blueTgArn = sh(
                script: "aws elbv2 describe-target-groups --names ${blueTgName} --query 'TargetGroups[0].TargetGroupArn' --output text",
                returnStdout: true
            ).trim()
        }
        
        // Configure ALB to route to blue target group
        sh """
        # Get ALB and listener ARNs
        ALB_ARN=\$(aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text)
        LISTENER_ARN=\$(aws elbv2 describe-listeners --load-balancer-arn \$ALB_ARN --query 'Listeners[?Port==`80`].ListenerArn' --output text)
        """
        
        // For app_1, use default action; for other apps, create path-based rules
        if (appName == "app_1") {
            sh """
            # Configure default action to route to Blue target group
            aws elbv2 modify-listener --listener-arn \$LISTENER_ARN --default-actions Type=forward,TargetGroupArn=${blueTgArn}
            """
        } else {
            // Find an available priority
            def usedPriorities = sh(
                script: """
                aws elbv2 describe-rules --listener-arn \$LISTENER_ARN --query 'Rules[?Priority!=`default`].Priority' --output json
                """,
                returnStdout: true
            ).trim()
            
            def usedPrioritiesJson = initialDeploymentParseJson(usedPriorities)
            def priority = 50  // Start with a lower priority for app routing
            
            // Find the first available priority
            while (usedPrioritiesJson.contains(priority.toString())) {
                priority++
            }
            
            // Create path-based rule for this app
            sh """
            aws elbv2 create-rule \\
                --listener-arn \$LISTENER_ARN \\
                --priority ${priority} \\
                --conditions '[{"Field":"path-pattern","Values":["/app${appSuffix}/*"]}]' \\
                --actions '[{"Type":"forward","TargetGroupArn":"${blueTgArn}"}]'
            """
            echo "Created path-based rule with priority ${priority} for app${appSuffix}"
        }
        
        // Wait for service to stabilize
        sh "aws ecs wait services-stable --cluster blue-green-cluster --services \"${blueService}\" --region ${config.awsRegion}"
        
        echo "✅ Initial deployment of ${appName} completed successfully! Application is now accessible through the ALB."
    } catch (Exception e) {
        echo "❌ Initial deployment failed: ${e.message}"
        throw e
    }
}

@NonCPS
def initialDeploymentParseJson(String jsonText) {
    def parsed = new JsonSlurper().parseText(jsonText)
    def safeMap = [:]
    safeMap.putAll(parsed)
    return safeMap
}

@NonCPS
def initialDeploymentGetField(String jsonText, String fieldName) {
    def parsed = new JsonSlurper().parseText(jsonText)
    return parsed?."${fieldName}"?.toString()
}

@NonCPS
def initialDeploymentUpdateTaskDef(String jsonText, String imageUri) {
    def taskDef = new JsonSlurper().parseText(jsonText)
    ['taskDefinitionArn', 'revision', 'status', 'requiresAttributes', 'compatibilities',
     'registeredAt', 'registeredBy', 'deregisteredAt'].each { field ->
        taskDef.remove(field)
    }
    taskDef.containerDefinitions[0].image = imageUri
    return JsonOutput.prettyPrint(JsonOutput.toJson(taskDef))
}