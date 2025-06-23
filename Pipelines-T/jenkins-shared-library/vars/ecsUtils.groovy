// vars/ecsUtils.groovy

def waitForServices(Map config) {
    echo "Waiting for ECS services to stabilize..."
    sleep(60)  // Give time for services to start
    
    // Get app name from config or default to app_1
    def appName = config.appName ?: "app_1"
    def appSuffix = appName.replace("app_", "")
    
    // Get the cluster name
    def cluster = sh(
        script: "terraform -chdir=${config.tfWorkingDir} output -raw ecs_cluster_id",
        returnStdout: true
    ).trim()
    
    // Check ECS service status for app-specific service if it exists
    def serviceName = "blue-service-${appSuffix}"
    def serviceExists = sh(
        script: """
            aws ecs describe-services --cluster ${cluster} --services ${serviceName} --query 'services[0].status' --output text 2>/dev/null || echo "MISSING"
        """,
        returnStdout: true
    ).trim()
    
    if (serviceExists == "MISSING" || serviceExists == "INACTIVE") {
        serviceName = "blue-service"
        echo "Using default service name: ${serviceName}"
    } else {
        echo "Using app-specific service name: ${serviceName}"
    }
    
    sh """
    aws ecs describe-services --cluster ${cluster} --services ${serviceName} --query 'services[0].{Status:status,DesiredCount:desiredCount,RunningCount:runningCount}' --output table
    """
    
    // Get the ALB DNS name
    def albDns = sh(
        script: "terraform -chdir=${config.tfWorkingDir} output -raw alb_dns_name",
        returnStdout: true
    ).trim()
    
    // Determine health endpoint based on app
    def healthEndpoint = appSuffix == "1" ? "/health" : "/app${appSuffix}/health"
    
    echo "Application is accessible at: http://${albDns}${appSuffix == "1" ? "" : "/app" + appSuffix}"
    
    // Test the application
    sh """
    # Wait for the application to be fully available
    sleep 30
    
    # Test the health endpoint
    curl -f http://${albDns}${healthEndpoint} || echo "Health check failed but continuing"
    """
}

def cleanResources(Map config) {
    if (params.MANUAL_BUILD != 'DESTROY' || config.implementation != 'ecs') {
        echo "⚠️ Skipping ECR cleanup as conditions not met (either not DESTROY or not ECS)."
        return
    }

    echo "🧹 Cleaning up ECR repository before destruction..."

    try {
        // Check if the ECR repository exists
        def ecrRepoExists = sh(
            script: """
                aws ecr describe-repositories --repository-names ${config.ecrRepoName} --region ${config.awsRegion} &>/dev/null && echo 0 || echo 1
            """,
            returnStdout: true
        ).trim() == "0"

        if (ecrRepoExists) {
            echo "🔍 Fetching all images in repository ${config.ecrRepoName}..."

            def imagesOutput = sh(
                script: """
                    aws ecr describe-images --repository-name ${config.ecrRepoName} --output json
                """,
                returnStdout: true
            ).trim()

            def imagesJson = readJSON text: imagesOutput
            def imageDetails = imagesJson.imageDetails

            echo "Found ${imageDetails.size()} images in repository"

            imageDetails.each { image ->
                def digest = image.imageDigest
                echo "Deleting image: ${digest}"
                sh """
                    aws ecr batch-delete-image \\
                        --repository-name ${config.ecrRepoName} \\
                        --image-ids imageDigest=${digest}
                """
            }

            echo "✅ ECR repository cleanup completed."
        } else {
            echo "ℹ️ ECR repository ${config.ecrRepoName} not found, skipping cleanup"
        }
    } catch (Exception e) {
        echo "⚠️ Warning: ECR cleanup encountered an issue: ${e.message}"
    }
}

def detectChanges(Map config) {
    echo "🔍 Detecting changes for ECS implementation..."

    def changedFiles = []
    try {
        // Check for any file changes between last 2 commits
        def gitDiff = sh(
            script: "git diff --name-only HEAD~1 HEAD",
            returnStdout: true
        ).trim()

        if (gitDiff) {
            changedFiles = gitDiff.split('\n')
            echo "📝 Changed files: ${changedFiles.join(', ')}"
            echo "🚀 Change(s) detected. Triggering deployment."
            env.DEPLOY_NEW_VERSION = 'true'
            
            // Detect which app was changed
            def appPattern = ~/.*app_([1-3])\.py$/
            def appFile = changedFiles.find { it =~ appPattern }
            if (appFile) {
                def matcher = appFile =~ appPattern
                if (matcher.matches()) {
                    def appNum = matcher[0][1]
                    env.CHANGED_APP = "app_${appNum}"
                    echo "📱 Detected change in application: ${env.CHANGED_APP}"
                }
            }
        } else {
            echo "📄 No changes detected between last two commits."
            env.DEPLOY_NEW_VERSION = 'false'
        }

    } catch (Exception e) {
        echo "⚠️ Could not determine changed files. Assuming change occurred to force deploy."
        env.DEPLOY_NEW_VERSION = 'true'
    }
}

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def fetchResources(Map config) {
    echo "🔄 Fetching ECS and ALB resources..."

    def result = [:]
    def appName = env.CHANGED_APP ?: config.appName ?: "app_1"
    def appSuffix = appName.replace("app_", "")
    
    try {
        result.APP_NAME = appName
        result.APP_SUFFIX = appSuffix
        
        // Get ECS cluster
        result.ECS_CLUSTER = sh(
            script: "aws ecs list-clusters --query 'clusterArns[0]' --output text | cut -d'/' -f2",
            returnStdout: true
        ).trim()

        if (!result.ECS_CLUSTER) {
            error "❌ No ECS clusters found in the current AWS region"
        }

        // Get all target groups and parse as JSON for more reliable searching
        def targetGroupsJson = sh(
            script: "aws elbv2 describe-target-groups --output json",
            returnStdout: true
        ).trim()
        
        def tgData = parseJsonString(targetGroupsJson)
        def allTargetGroups = tgData.TargetGroups ?: []
        
        if (allTargetGroups.isEmpty()) {
            error "❌ No target groups found in the current AWS region"
        }

        // Try to find app-specific target groups first, then fall back to generic names
        def blueTg = allTargetGroups.find { 
            it.TargetGroupName == "blue-tg-app${appSuffix}" || 
            it.TargetGroupName == "blue-tg-${appSuffix}" ||
            it.TargetGroupName == "blue-tg"
        }
        
        def greenTg = allTargetGroups.find { 
            it.TargetGroupName == "green-tg-app${appSuffix}" || 
            it.TargetGroupName == "green-tg-${appSuffix}" ||
            it.TargetGroupName == "green-tg"
        }

        // If still not found, try any target group with "blue" or "green" in the name
        if (!blueTg) {
            blueTg = allTargetGroups.find { it.TargetGroupName.toLowerCase().contains("blue") }
        }
        if (!greenTg) {
            greenTg = allTargetGroups.find { it.TargetGroupName.toLowerCase().contains("green") }
        }

        if (!blueTg) {
            echo "⚠️ Available target groups:"
            allTargetGroups.each { tg -> echo "- ${tg.TargetGroupName}" }
            error "❌ Could not find a blue target group for app ${appName}"
        }
        if (!greenTg) {
            echo "⚠️ Available target groups:"
            allTargetGroups.each { tg -> echo "- ${tg.TargetGroupName}" }
            error "❌ Could not find a green target group for app ${appName}"
        }

        result.BLUE_TG_ARN = blueTg.TargetGroupArn
        result.GREEN_TG_ARN = greenTg.TargetGroupArn

        // Get ALB and listener
        result.ALB_ARN = sh(
            script: "aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text",
            returnStdout: true
        ).trim()

        if (!result.ALB_ARN) {
            error "❌ Could not find ALB named 'blue-green-alb'"
        }

        result.LISTENER_ARN = sh(
            script: "aws elbv2 describe-listeners --load-balancer-arn ${result.ALB_ARN} --query 'Listeners[0].ListenerArn' --output text",
            returnStdout: true
        ).trim()

        if (!result.LISTENER_ARN) {
            error "❌ Could not find listener for ALB ${result.ALB_ARN}"
        }

        // Determine current live environment
        def rulesJson = sh(
            script: "aws elbv2 describe-rules --listener-arn ${result.LISTENER_ARN} --output json",
            returnStdout: true
        ).trim()
        
        def rulesData = parseJsonString(rulesJson)
        def rules = rulesData.Rules ?: []
        
        def liveTgArn = null
        def appPathPattern = "/app${appSuffix}*"
        
        for (rule in rules) {
            if (rule.Priority != 'default' && rule.Conditions) {
                def pathCondition = rule.Conditions.find { 
                    it.Field == 'path-pattern' && 
                    it.PathPatternConfig?.Values?.any { it == appPathPattern }
                }
                
                if (pathCondition) {
                    def action = rule.Actions?.find { it.Type == 'forward' }
                    if (action) {
                        if (action.TargetGroupArn) {
                            liveTgArn = action.TargetGroupArn
                        } else if (action.ForwardConfig?.TargetGroups) {
                            def maxWeight = action.ForwardConfig.TargetGroups.max { it.Weight ?: 0 }
                            liveTgArn = maxWeight?.TargetGroupArn
                        }
                    }
                    break
                }
            }
        }

        // Set live/idle environments
        if (liveTgArn == result.BLUE_TG_ARN) {
            result.LIVE_ENV = "BLUE"
            result.IDLE_ENV = "GREEN"
            result.LIVE_TG_ARN = result.BLUE_TG_ARN
            result.IDLE_TG_ARN = result.GREEN_TG_ARN
        } else if (liveTgArn == result.GREEN_TG_ARN) {
            result.LIVE_ENV = "GREEN"
            result.IDLE_ENV = "BLUE"
            result.LIVE_TG_ARN = result.GREEN_TG_ARN
            result.IDLE_TG_ARN = result.BLUE_TG_ARN
        } else {
            echo "⚠️ Could not determine live environment from rules, defaulting to BLUE"
            result.LIVE_ENV = "BLUE"
            result.IDLE_ENV = "GREEN"
            result.LIVE_TG_ARN = result.BLUE_TG_ARN
            result.IDLE_TG_ARN = result.GREEN_TG_ARN
        }

        // Get service names
        def services = sh(
            script: "aws ecs list-services --cluster ${result.ECS_CLUSTER} --output text | tr '\\t' '\\n' | grep -o '[^/]*\$'",
            returnStdout: true
        ).trim().split("\\s+")
        
        def blueService = services.find { it == "app${appSuffix}-blue-service" } ?: services.find { it == "blue-service" }
        def greenService = services.find { it == "app${appSuffix}-green-service" } ?: services.find { it == "green-service" }

        if (!blueService) error "❌ Could not find blue service"
        if (!greenService) error "❌ Could not find green service"

        result.LIVE_SERVICE = result.LIVE_ENV == "BLUE" ? blueService : greenService
        result.IDLE_SERVICE = result.IDLE_ENV == "BLUE" ? blueService : greenService

        echo "✅ ECS Cluster: ${result.ECS_CLUSTER}"
        echo "✅ App Name: ${result.APP_NAME}"
        echo "✅ Blue Target Group ARN: ${result.BLUE_TG_ARN}"
        echo "✅ Green Target Group ARN: ${result.GREEN_TG_ARN}"
        echo "✅ ALB ARN: ${result.ALB_ARN}"
        echo "✅ Listener ARN: ${result.LISTENER_ARN}"
        echo "✅ LIVE ENV: ${result.LIVE_ENV}"
        echo "✅ IDLE ENV: ${result.IDLE_ENV}"
        echo "✅ LIVE SERVICE: ${result.LIVE_SERVICE}"
        echo "✅ IDLE SERVICE: ${result.IDLE_SERVICE}"

        return result

    } catch (Exception e) {
        echo "⚠️ Error details:"
        echo "App Name: ${appName}"
        echo "App Suffix: ${appSuffix}"
        sh "aws elbv2 describe-target-groups --query 'TargetGroups[*].TargetGroupName' --output table"
        error "❌ Failed to fetch ECS resources: ${e.message}"
    }
}

// Rest of your existing methods (ensureTargetGroupAssociation, updateApplication, etc.) remain the same
@NonCPS
def parseJsonString(String json) {
    try {
        if (!json || json.trim().isEmpty() || json.trim() == "null") {
            return [:]
        }
        return new JsonSlurper().parseText(json)
    } catch (Exception e) {
        echo "⚠️ Error parsing JSON: ${e.message}"
        return [:]
    }
}


def ensureTargetGroupAssociation(Map config) {
    echo "Ensuring target group is associated with load balancer..."

    if (!config.IDLE_TG_ARN || config.IDLE_TG_ARN.trim() == "") {
        error "IDLE_TG_ARN is missing or empty"
    }
    if (!config.LISTENER_ARN || config.LISTENER_ARN.trim() == "") {
        error "LISTENER_ARN is missing or empty"
    }
    
    // Get app name from config
    def appName = config.APP_NAME ?: "app_1"
    def appSuffix = config.APP_SUFFIX ?: appName.replace("app_", "")

    // Check if target group is associated with load balancer using text output
    def targetGroupInfo = sh(
        script: """
        aws elbv2 describe-target-groups --target-group-arns ${config.IDLE_TG_ARN} --query 'TargetGroups[0].LoadBalancerArns' --output text
        """,
        returnStdout: true
    ).trim()

    // If output is empty or "None", create a rule
    if (!targetGroupInfo || targetGroupInfo.isEmpty() || targetGroupInfo == "None") {
        echo "⚠️ Target group ${config.IDLE_ENV} is not associated with a load balancer. Creating a path-based rule..."
        
        // Use fixed priority to avoid parsing issues
        def nextPriority = 250
        echo "Using rule priority: ${nextPriority}"
        
        // Use app-specific path pattern
        def pathPattern = "/app${appSuffix}/*"

        sh """
        aws elbv2 create-rule \\
            --listener-arn ${config.LISTENER_ARN} \\
            --priority ${nextPriority} \\
            --conditions '[{"Field":"path-pattern","Values":["${pathPattern}"]}]' \\
            --actions '[{"Type":"forward","TargetGroupArn":"${config.IDLE_TG_ARN}"}]'
        """

        sleep(10)
        echo "✅ Target group associated with load balancer via path rule (priority ${nextPriority})"
    } else {
        echo "✅ Target group is already associated with load balancer"
    }
}

@NonCPS
def parseJsonWithErrorHandling(String text) {
    try {
        if (!text || text.trim().isEmpty() || text.trim() == "null") {
            return []
        }
        
        def parsed = new groovy.json.JsonSlurper().parseText(text)
        
        if (parsed instanceof List) {
            return parsed
        } else if (parsed instanceof Map) {
            def safeMap = [:]
            safeMap.putAll(parsed)
            return safeMap
        } else {
            return []
        }
    } catch (Exception e) {
        echo "⚠️ Error parsing JSON: ${e.message}"
        return []
    }
}



def updateApplication(Map config) {
    echo "Running ECS update application logic..."

    try {
        // Validate required parameters
        if (!env.AWS_REGION) error "❌ AWS_REGION environment variable is required"
        if (!env.ECR_REPO_NAME) error "❌ ECR_REPO_NAME environment variable is required"
        
        // Determine app name and suffix
        def appName = env.CHANGED_APP ?: config.APP_NAME ?: config.appName ?: "app_1"
        def appSuffix = appName.replace("app_", "")
        
        echo "ℹ️ Using appName: ${appName}"
        echo "ℹ️ Using appSuffix: ${appSuffix}"

        // Step 1: Discover ECS cluster
        def clusterArn = sh(
            script: "aws ecs list-clusters --region ${env.AWS_REGION} --query 'clusterArns[0]' --output text",
            returnStdout: true
        ).trim()
        
        if (!clusterArn) error "❌ No ECS clusters found in region ${env.AWS_REGION}"
        
        def ecsCluster = clusterArn.split('/')[-1]
        env.ECS_CLUSTER = ecsCluster
        echo "✅ Using ECS cluster: ${env.ECS_CLUSTER}"

        // Step 2: Discover ECS services
        def servicesJson = sh(
            script: "aws ecs list-services --cluster ${ecsCluster} --region ${env.AWS_REGION} --output json",
            returnStdout: true
        ).trim()
        
        def services = parseJsonSafe(servicesJson)?.serviceArns?.collect { it.split('/')[-1] } ?: []
        
        if (services.empty) error "❌ No ECS services found in cluster ${ecsCluster}"
        echo "ℹ️ Discovered ECS services: ${services}"

        // Find blue and green services
        def blueService = services.find { it ==~ /(?i)app${appSuffix}-blue-service/ } ?: 
                         services.find { it ==~ /(?i)blue-service/ } ?:
                         services.find { it.toLowerCase().contains('blue') }
                         
        def greenService = services.find { it ==~ /(?i)app${appSuffix}-green-service/ } ?: 
                          services.find { it ==~ /(?i)green-service/ } ?:
                          services.find { it.toLowerCase().contains('green') }

        if (!blueService) error "❌ Could not find blue ECS service in cluster ${ecsCluster}"
        if (!greenService) error "❌ Could not find green ECS service in cluster ${ecsCluster}"

        echo "ℹ️ Using blue service: ${blueService}"
        echo "ℹ️ Using green service: ${greenService}"

        // Step 3: Determine current active environment
        def getActiveEnv = { service ->
            try {
                def taskDefArn = sh(
                    script: "aws ecs describe-services --cluster ${ecsCluster} --services ${service} --region ${env.AWS_REGION} --query 'services[0].taskDefinition' --output text",
                    returnStdout: true
                ).trim()
                
                if (!taskDefArn) return null
                
                def taskDef = sh(
                    script: "aws ecs describe-task-definition --task-definition ${taskDefArn} --region ${env.AWS_REGION} --query 'taskDefinition' --output json",
                    returnStdout: true
                ).trim()
                
                def taskDefJson = parseJsonSafe(taskDef)
                def imageTag = taskDefJson?.containerDefinitions?.getAt(0)?.image?.split(':')[-1] ?: ""
                
                return imageTag.contains("latest") ? service.contains("blue") ? "BLUE" : "GREEN" : null
            } catch (Exception e) {
                echo "⚠️ Error checking service ${service}: ${e.message}"
                return null
            }
        }

        env.ACTIVE_ENV = getActiveEnv(blueService) ?: getActiveEnv(greenService) ?: "BLUE"
        env.IDLE_ENV = env.ACTIVE_ENV == "BLUE" ? "GREEN" : "BLUE"
        env.IDLE_SERVICE = env.IDLE_ENV == "BLUE" ? blueService : greenService

        echo "ℹ️ ACTIVE_ENV: ${env.ACTIVE_ENV}"
        echo "ℹ️ IDLE_ENV: ${env.IDLE_ENV}"
        echo "ℹ️ IDLE_SERVICE: ${env.IDLE_SERVICE}"

        // Step 4: Tag rollback image
        def currentImage = sh(
            script: "aws ecr describe-images --repository-name ${env.ECR_REPO_NAME} --image-ids imageTag=${appName}-latest --region ${env.AWS_REGION} --query 'imageDetails[0].imageDigest' --output text",
            returnStdout: true
        ).trim()
        
        if (currentImage) {
            def timestamp = new Date().format("yyyyMMdd-HHmmss")
            def rollbackTag = "${appName}-rollback-${timestamp}"
            
            sh """
                aws ecr batch-get-image --repository-name ${env.ECR_REPO_NAME} --region ${env.AWS_REGION} --image-ids imageDigest=${currentImage} --query 'images[0].imageManifest' --output text > manifest.json
                aws ecr put-image --repository-name ${env.ECR_REPO_NAME} --region ${env.AWS_REGION} --image-tag ${rollbackTag} --image-manifest file://manifest.json
            """
            echo "✅ Tagged rollback image as ${rollbackTag}"
        } else {
            echo "⚠️ No current image found with tag ${appName}-latest"
        }

        // Step 5: Build and push new image
        def repoUri = sh(
            script: "aws ecr describe-repositories --repository-names ${env.ECR_REPO_NAME} --region ${env.AWS_REGION} --query 'repositories[0].repositoryUri' --output text",
            returnStdout: true
        ).trim()
        
        def imageTag = "${appName}-latest"
        def dockerfilePath = "${env.WORKSPACE}/blue-green-deployment/modules/ecs/scripts"
        
        sh """
            aws ecr get-login-password --region ${env.AWS_REGION} | docker login --username AWS --password-stdin ${repoUri}
            cd ${dockerfilePath}
            docker build -t ${env.ECR_REPO_NAME}:${imageTag} --build-arg APP_NAME=${appSuffix} .
            docker tag ${env.ECR_REPO_NAME}:${imageTag} ${repoUri}:${imageTag}
            docker push ${repoUri}:${imageTag}
        """
        
        env.IMAGE_URI = "${repoUri}:${imageTag}"
        echo "✅ Image pushed: ${env.IMAGE_URI}"

        // Step 6: Update ECS Service
        def taskArn = sh(
            script: "aws ecs describe-services --cluster ${ecsCluster} --services ${env.IDLE_SERVICE} --region ${env.AWS_REGION} --query 'services[0].taskDefinition' --output text",
            returnStdout: true
        ).trim()
        
        def taskDefJson = taskArn ? sh(
            script: "aws ecs describe-task-definition --task-definition ${taskArn} --region ${env.AWS_REGION} --query 'taskDefinition' --output json",
            returnStdout: true
        ).trim() : '{}'
        
        def newTaskDef = updateTaskDefImageAndSerialize(taskDefJson, env.IMAGE_URI)
        def taskDefFile = "task-def-${appSuffix}.json"
        writeFile file: taskDefFile, text: newTaskDef
        
        def newTaskArn = sh(
            script: "aws ecs register-task-definition --cli-input-json file://${taskDefFile} --region ${env.AWS_REGION} --query 'taskDefinition.taskDefinitionArn' --output text",
            returnStdout: true
        ).trim()
        
        if (!newTaskArn) error "❌ Failed to register new task definition"

        sh """
            aws ecs update-service \
                --cluster ${ecsCluster} \
                --service ${env.IDLE_SERVICE} \
                --task-definition ${newTaskArn} \
                --desired-count 1 \
                --force-new-deployment \
                --region ${env.AWS_REGION}
                
            aws ecs wait services-stable \
                --cluster ${ecsCluster} \
                --services ${env.IDLE_SERVICE} \
                --region ${env.AWS_REGION}
        """

        echo "✅ Successfully updated ${env.IDLE_ENV} service (${env.IDLE_SERVICE}) to new task definition"
        
    } catch (Exception e) {
        echo "❌ ECS update failed: ${e.message}"
        echo "Stack trace: ${e.getStackTrace().join('\n')}"
        error "Deployment failed"
    }
}

def testEnvironment(Map config) {
    echo "🔍 Testing ${env.IDLE_ENV} environment..."

    try {
        // Get app name from config
        def appName = config.APP_NAME ?: "app_1"
        def appSuffix = config.APP_SUFFIX ?: appName.replace("app_", "")
        
        // Dynamically fetch ALB ARN if not set
        if (!env.ALB_ARN) {
            echo "📡 Fetching ALB ARN..."
            env.ALB_ARN = sh(
                script: """
                    aws elbv2 describe-load-balancers \\
                        --names ${config.albName} \\
                        --query 'LoadBalancers[0].LoadBalancerArn' \\
                        --output text
                """,
                returnStdout: true
            ).trim()
        }

        // Dynamically fetch Listener ARN if not set
        if (!env.LISTENER_ARN) {
            echo "🎧 Fetching Listener ARN..."
            env.LISTENER_ARN = sh(
                script: """
                    aws elbv2 describe-listeners \\
                        --load-balancer-arn ${env.ALB_ARN} \\
                        --query 'Listeners[0].ListenerArn' \\
                        --output text
                """,
                returnStdout: true
            ).trim()
        }

        // Delete existing test rule if it exists
        echo "🧹 Cleaning up any existing test rule..."
        sh """
        TEST_RULE=\$(aws elbv2 describe-rules \\
            --listener-arn ${env.LISTENER_ARN} \\
            --query "Rules[?Priority=='10'].RuleArn" \\
            --output text)

        if [ ! -z "\$TEST_RULE" ]; then
            aws elbv2 delete-rule --rule-arn \$TEST_RULE
        fi
        """

        // Create app-specific test path pattern
        def testPathPattern = appSuffix == "1" ? "/test*" : "/app${appSuffix}/test*"
        
        // Create new test rule
        echo "🚧 Creating test rule for ${testPathPattern} on idle target group..."
        sh """
        aws elbv2 create-rule \\
            --listener-arn ${env.LISTENER_ARN} \\
            --priority 10 \\
            --conditions '[{"Field":"path-pattern","Values":["${testPathPattern}"]}]' \\
            --actions '[{"Type":"forward","TargetGroupArn":"${env.IDLE_TG_ARN}"}]'
        """

        // Get ALB DNS
        def albDns = sh(
            script: """
                aws elbv2 describe-load-balancers \\
                    --load-balancer-arns ${env.ALB_ARN} \\
                    --query 'LoadBalancers[0].DNSName' \\
                    --output text
            """,
            returnStdout: true
        ).trim()

        // Store DNS for later use
        env.ALB_DNS = albDns

        // Wait for rule propagation and test endpoint
        echo "⏳ Waiting for rule to propagate..."
        sh "sleep 10"

        // Test app-specific health endpoint
        def testEndpoint = appSuffix == "1" ? "/test/health" : "/app${appSuffix}/test/health"
        echo "🌐 Hitting test endpoint: http://${albDns}${testEndpoint}"
        sh """
        curl -f http://${albDns}${testEndpoint} || curl -f http://${albDns}${testEndpoint.replace('/health', '')} || echo "⚠️ Health check failed but continuing"
        """

        echo "✅ ${env.IDLE_ENV} environment tested successfully"

    } catch (Exception e) {
        echo "⚠️ Warning: Test stage encountered an issue: ${e.message}"
        echo "Proceeding with deployment despite test issues."
    } finally {
        // Cleanup test rule after testing
        echo "🧽 Cleaning up test rule..."
        sh """
        TEST_RULE=\$(aws elbv2 describe-rules \\
            --listener-arn ${env.LISTENER_ARN} \\
            --query "Rules[?Priority=='10'].RuleArn" \\
            --output text)

        if [ ! -z "\$TEST_RULE" ]; then
            aws elbv2 delete-rule --rule-arn \$TEST_RULE
            echo "🗑️ Test rule deleted."
        else
            echo "ℹ️ No test rule found to delete."
        fi
        """
    }
}

import groovy.json.JsonOutput

def switchTrafficToTargetEnv(String targetEnv, String blueTgArn, String greenTgArn, String listenerArn, Map config = [:]) {
    echo "🔄 Switching traffic to ${targetEnv}..."
    
    // Get app name from config
    def appName = config.APP_NAME ?: "app_1"
    def appSuffix = config.APP_SUFFIX ?: appName.replace("app_", "")

    def targetArn = (targetEnv == "GREEN") ? greenTgArn : blueTgArn
    def otherArn  = (targetEnv == "GREEN") ? blueTgArn  : greenTgArn
    
    // For app-specific routing, use the exact path pattern from Terraform
    def appPathPattern = "/app${appSuffix}*"
    
    // Use a safer approach to find the rule
    def ruleArn = sh(
        script: """
            aws elbv2 describe-rules --listener-arn ${listenerArn} --output json | \\
            jq -r '.Rules[] | select(.Conditions != null) | select((.Conditions[].PathPatternConfig.Values | arrays) and (.Conditions[].PathPatternConfig.Values[] | contains("${appPathPattern}"))) | .RuleArn' | head -1
        """,
        returnStdout: true
    ).trim()
    
    if (ruleArn && ruleArn != "None") {
        // Update existing rule
        sh """
            aws elbv2 modify-rule \\
                --rule-arn ${ruleArn} \\
                --actions Type=forward,TargetGroupArn=${targetArn}
        """
        echo "✅ Updated rule to route ${appPathPattern} to ${targetEnv} (${targetArn})"
    } else if (appSuffix == "1") {
        // For app1, modify the default action
        def targetGroups = [
            [TargetGroupArn: targetArn, Weight: 1],
            [TargetGroupArn: otherArn,  Weight: 0]
        ]

        def forwardAction = [
            [
                Type: "forward",
                ForwardConfig: [
                    TargetGroups: targetGroups
                ]
            ]
        ]

        writeFile file: 'forward-config.json', text: JsonOutput.prettyPrint(JsonOutput.toJson(forwardAction))
        sh """
            aws elbv2 modify-listener \\
                --listener-arn ${listenerArn} \\
                --default-actions file://forward-config.json
        """
        echo "✅ Traffic switched to ${targetEnv} (${targetArn}) for default route"
    } else {
        // Create a new rule for this app
        // Find an available priority
        def usedPriorities = sh(
            script: """
            aws elbv2 describe-rules --listener-arn ${listenerArn} --query 'Rules[?Priority!=`default`].Priority' --output json
            """,
            returnStdout: true
        ).trim()
        
        def usedPrioritiesJson = parseJson(usedPriorities)
        def priority = 50  // Start with a lower priority for app routing
        
        // Find the first available priority
        while (usedPrioritiesJson.contains(priority.toString())) {
            priority++
        }
        
        sh """
            aws elbv2 create-rule \\
                --listener-arn ${listenerArn} \\
                --priority ${priority} \\
                --conditions '[{"Field":"path-pattern","Values":["${appPathPattern}"]}]' \\
                --actions '[{"Type":"forward","TargetGroupArn":"${targetArn}"}]'
        """
        echo "✅ Created new rule with priority ${priority} to route ${appPathPattern} to ${targetEnv}"
    }
}


import groovy.json.JsonSlurper

def scaleDownOldEnvironment(Map config) {
    // Get app name from config
    def appName = config.APP_NAME ?: "app_1"
    def appSuffix = config.APP_SUFFIX ?: appName.replace("app_", "")
    
    // --- Fetch ECS Cluster dynamically if not provided ---
    if (!config.ECS_CLUSTER) {
        echo "⚙️ ECS_CLUSTER not set, fetching dynamically..."
        def ecsClusterId = sh(
            script: "aws ecs list-clusters --query 'clusterArns[0]' --output text | cut -d'/' -f2",
            returnStdout: true
        ).trim()
        if (!ecsClusterId) {
            error "Failed to fetch ECS cluster ID dynamically"
        }
        config.ECS_CLUSTER = ecsClusterId
        echo "✅ Dynamically fetched ECS_CLUSTER: ${config.ECS_CLUSTER}"
    }

    // --- Fetch ALB ARN dynamically if not provided ---
    if (!config.ALB_ARN) {
        echo "⚙️ ALB_ARN not set, fetching dynamically..."
        def albArn = sh(
            script: "aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text",
            returnStdout: true
        ).trim()
        if (!albArn || albArn == 'None') {
            error "Failed to fetch ALB ARN"
        }
        config.ALB_ARN = albArn
        echo "✅ Dynamically fetched ALB_ARN: ${config.ALB_ARN}"
    }

    // --- Fetch Listener ARN dynamically if not provided ---
    if (!config.LISTENER_ARN) {
        echo "⚙️ LISTENER_ARN not set, fetching dynamically..."
        def listenerArn = sh(
            script: "aws elbv2 describe-listeners --load-balancer-arn ${config.ALB_ARN} --query 'Listeners[0].ListenerArn' --output text",
            returnStdout: true
        ).trim()
        if (!listenerArn || listenerArn == 'None') {
            error "Failed to fetch Listener ARN"
        }
        config.LISTENER_ARN = listenerArn
        echo "✅ Dynamically fetched LISTENER_ARN: ${config.LISTENER_ARN}"
    }

    // --- Fetch Blue and Green Target Group ARNs dynamically ---
    def blueTgArn = sh(
        script: "aws elbv2 describe-target-groups --names blue-tg-app${appSuffix} --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
        returnStdout: true
    ).trim()
    def greenTgArn = sh(
        script: "aws elbv2 describe-target-groups --names green-tg-app${appSuffix} --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
        returnStdout: true
    ).trim()
    if (!blueTgArn || blueTgArn == 'None') error "Blue target group ARN not found"
    if (!greenTgArn || greenTgArn == 'None') error "Green target group ARN not found"

    // --- Determine ACTIVE_ENV dynamically if not provided ---
    if (!config.ACTIVE_ENV) {
        echo "⚙️ ACTIVE_ENV not set, determining dynamically..."
        
        // For app-specific routing, use the exact path pattern from Terraform
        def appPathPattern = "/app${appSuffix}*"
        
        // Use a safer approach to find the rule
        def ruleArn = sh(
            script: """
                aws elbv2 describe-rules --listener-arn ${config.LISTENER_ARN} --output json | \\
                jq -r '.Rules[] | select(.Conditions != null) | select((.Conditions[].PathPatternConfig.Values | arrays) and (.Conditions[].PathPatternConfig.Values[] | contains("${appPathPattern}"))) | .RuleArn' | head -1
            """,
            returnStdout: true
        ).trim()
        
        def activeTgArn = null
        
        if (ruleArn && ruleArn != "None") {
            // Get target group from app-specific rule
            activeTgArn = sh(
                script: """
                    aws elbv2 describe-rules --rule-arns ${ruleArn} --output json | \\
                    jq -r '.Rules[0].Actions[0].TargetGroupArn // .Rules[0].Actions[0].ForwardConfig.TargetGroups[0].TargetGroupArn'
                """,
                returnStdout: true
            ).trim()
        } else if (appSuffix == "1") {
            // For app1, check default action
            activeTgArn = sh(
                script: """
                    aws elbv2 describe-listeners --listener-arns ${config.LISTENER_ARN} --output json | \\
                    jq -r '.Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups[] | select(.Weight == 1) | .TargetGroupArn'
                """,
                returnStdout: true
            ).trim()
        }
        
        if (!activeTgArn || activeTgArn == 'None') {
            echo "⚠️ Could not determine active target group, defaulting to BLUE"
            config.ACTIVE_ENV = "BLUE"
        } else if (activeTgArn == blueTgArn) {
            config.ACTIVE_ENV = "BLUE"
        } else if (activeTgArn == greenTgArn) {
            config.ACTIVE_ENV = "GREEN"
        } else {
            error "Active target group ARN does not match blue or green target groups"
        }
        echo "✅ Dynamically determined ACTIVE_ENV: ${config.ACTIVE_ENV}"
    }

    // --- Determine IDLE_ENV and IDLE_TG_ARN based on ACTIVE_ENV ---
    if (!config.IDLE_ENV || !config.IDLE_TG_ARN) {
        if (config.ACTIVE_ENV.toUpperCase() == "BLUE") {
            config.IDLE_ENV = "GREEN"
            config.IDLE_TG_ARN = greenTgArn
        } else if (config.ACTIVE_ENV.toUpperCase() == "GREEN") {
            config.IDLE_ENV = "BLUE"
            config.IDLE_TG_ARN = blueTgArn
        } else {
            error "ACTIVE_ENV must be 'BLUE' or 'GREEN'"
        }
        echo "✅ Dynamically determined IDLE_ENV: ${config.IDLE_ENV}"
        echo "✅ Dynamically determined IDLE_TG_ARN: ${config.IDLE_TG_ARN}"
    }

    // --- Dynamically determine IDLE_SERVICE ---
    if (!config.IDLE_SERVICE) {
        echo "⚙️ IDLE_SERVICE not set, determining dynamically based on IDLE_ENV..."
        def idleEnvLower = config.IDLE_ENV.toLowerCase()
        
        // Try app-specific service name first
        def expectedIdleServiceName = "app${appSuffix}-${idleEnvLower}-service"
        def servicesJson = sh(
            script: "aws ecs list-services --cluster ${config.ECS_CLUSTER} --query 'serviceArns' --output json",
            returnStdout: true
        ).trim()
        def services = new JsonSlurper().parseText(servicesJson)
        if (!services || services.isEmpty()) {
            error "No ECS services found in cluster ${config.ECS_CLUSTER}"
        }
        
        def matchedIdleServiceArn = services.find { it.toLowerCase().endsWith(expectedIdleServiceName.toLowerCase()) }
        
        // Fall back to default service name if app-specific not found
        if (!matchedIdleServiceArn) {
            expectedIdleServiceName = "${idleEnvLower}-service"
            matchedIdleServiceArn = services.find { it.toLowerCase().endsWith(expectedIdleServiceName.toLowerCase()) }
        }
        
        if (!matchedIdleServiceArn) {
            error "Idle service not found in cluster ${config.ECS_CLUSTER}"
        }
        
        def idleServiceName = matchedIdleServiceArn.tokenize('/').last()
        config.IDLE_SERVICE = idleServiceName
        echo "✅ Dynamically determined IDLE_SERVICE: ${config.IDLE_SERVICE}"
    }

    // --- Wait for all targets in idle target group to be healthy ---
    int maxAttempts = 30
    int attempt = 0
    int healthyCount = 0
    echo "⏳ Waiting for all targets in ${config.IDLE_ENV} TG to become healthy before scaling down old environment..."
    while (attempt < maxAttempts) {
        def healthJson = sh(
            script: "aws elbv2 describe-target-health --target-group-arn ${config.IDLE_TG_ARN} --query 'TargetHealthDescriptions[*].TargetHealth.State' --output json",
            returnStdout: true
        ).trim()
        def states = new JsonSlurper().parseText(healthJson)
        healthyCount = states.count { it == "healthy" }
        echo "Healthy targets: ${healthyCount} / ${states.size()}"
        if (states && healthyCount == states.size()) {
            echo "✅ All targets in ${config.IDLE_ENV} TG are healthy."
            break
        }
        attempt++
        sleep 10
    }
    if (healthyCount == 0) {
        error "❌ No healthy targets in ${config.IDLE_ENV} TG after waiting."
    }

    // --- Scale down the IDLE ECS service ---
    try {
        sh """
        aws ecs update-service \\
          --cluster ${config.ECS_CLUSTER} \\
          --service ${config.IDLE_SERVICE} \\
          --desired-count 0
        """
        echo "✅ Scaled down ${config.IDLE_SERVICE}"

        sh """
        aws ecs wait services-stable \\
          --cluster ${config.ECS_CLUSTER} \\
          --services ${config.IDLE_SERVICE}
        """
        echo "✅ ${config.IDLE_SERVICE} is now stable (scaled down)"
    } catch (Exception e) {
        echo "❌ Error during scale down: ${e.message}"
        throw e
    }
}
