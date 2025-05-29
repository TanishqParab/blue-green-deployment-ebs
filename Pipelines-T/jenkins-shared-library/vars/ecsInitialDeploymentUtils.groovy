// vars/ecsInitialDeploymentUtils.groovy

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def deployToBlueService(Map config) {
    echo "🚀 Deploying initial application to Blue Service..."

    try {
        // Get ECR repository URI
        def ecrUri = sh(
            script: "aws ecr describe-repositories --repository-names ${config.ecrRepoName} --region ${config.awsRegion} --query 'repositories[0].repositoryUri' --output text",
            returnStdout: true
        ).trim()
        
        // Build and push Docker image
        sh """
        aws ecr get-login-password --region ${config.awsRegion} | docker login --username AWS --password-stdin ${ecrUri}
        cd ${config.tfWorkingDir}/modules/ecs/scripts
        docker build -t ${config.ecrRepoName}:latest .
        docker tag ${config.ecrRepoName}:latest ${ecrUri}:latest
        docker push ${ecrUri}:latest
        """
        
        // Get blue service and task definition
        def blueService = sh(
            script: "aws ecs list-services --cluster blue-green-cluster --region ${config.awsRegion} --output json | jq -r '.serviceArns[] | select(contains(\"blue\"))' | cut -d'/' -f3",
            returnStdout: true
        ).trim()
        
        def taskDefArn = sh(
            script: "aws ecs describe-services --cluster blue-green-cluster --services ${blueService} --region ${config.awsRegion} --query 'services[0].taskDefinition' --output text",
            returnStdout: true
        ).trim()
        
        // Get task definition JSON
        def taskDefJsonText = sh(
            script: "aws ecs describe-task-definition --task-definition ${taskDefArn} --region ${config.awsRegion} --query 'taskDefinition' --output json",
            returnStdout: true
        ).trim()
        
        // Update task definition with new image
        def newTaskDefJson = initialDeploymentUpdateTaskDef(taskDefJsonText, "${ecrUri}:latest")
        writeFile file: 'initial-task-def.json', text: newTaskDefJson
        
        def newTaskDefArn = sh(
            script: "aws ecs register-task-definition --cli-input-json file://initial-task-def.json --region ${config.awsRegion} --query 'taskDefinition.taskDefinitionArn' --output text",
            returnStdout: true
        ).trim()
        
        // Update service with new task definition
        sh """
        aws ecs update-service \\
            --cluster blue-green-cluster \\
            --service ${blueService} \\
            --task-definition ${newTaskDefArn} \\
            --desired-count 1 \\
            --force-new-deployment \\
            --region ${config.awsRegion}
        """
        
        // Configure ALB to route to blue target group
        sh """
        # Get ALB and listener ARNs
        ALB_ARN=\$(aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text)
        LISTENER_ARN=\$(aws elbv2 describe-listeners --load-balancer-arn \$ALB_ARN --query 'Listeners[?Port==`80`].ListenerArn' --output text)
        BLUE_TG_ARN=\$(aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text)
        
        # Configure default action to route to Blue target group
        aws elbv2 modify-listener --listener-arn \$LISTENER_ARN --default-actions Type=forward,TargetGroupArn=\$BLUE_TG_ARN
        """
        
        // Wait for service to stabilize
        sh "aws ecs wait services-stable --cluster blue-green-cluster --services ${blueService} --region ${config.awsRegion}"
        
        echo "✅ Initial deployment completed successfully! Application is now accessible through the ALB."
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
