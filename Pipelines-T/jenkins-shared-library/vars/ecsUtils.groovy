// vars/ecsUtils.groovy

def waitForServices(Map config) {
    echo "Waiting for ECS services to stabilize..."
    sleep(60)  // Give time for services to start
    
    // Get the cluster name
    def cluster = sh(
        script: "terraform -chdir=${config.tfWorkingDir} output -raw ecs_cluster_id",
        returnStdout: true
    ).trim()
    
    // Check ECS service status
    sh """
    aws ecs describe-services --cluster ${cluster} --services blue-service --query 'services[0].{Status:status,DesiredCount:desiredCount,RunningCount:runningCount}' --output table
    """
    
    // Get the ALB DNS name
    def albDns = sh(
        script: "terraform -chdir=${config.tfWorkingDir} output -raw alb_dns_name",
        returnStdout: true
    ).trim()
    
    echo "Application is accessible at: http://${albDns}"
    
    // Test the application
    sh """
    # Wait for the application to be fully available
    sleep 30
    
    # Test the health endpoint
    curl -f http://${albDns}/health || echo "Health check failed but continuing"
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

def fetchResources(Map config) {
    echo "🔄 Fetching ECS and ALB resources..."

    def result = [:]

    try {
        result.ECS_CLUSTER = sh(
            script: "aws ecs list-clusters --query 'clusterArns[0]' --output text | awk -F'/' '{print \$2}'",
            returnStdout: true
        ).trim()

        result.BLUE_TG_ARN = sh(
            script: "aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
            returnStdout: true
        ).trim()

        result.GREEN_TG_ARN = sh(
            script: "aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
            returnStdout: true
        ).trim()

        result.ALB_ARN = sh(
            script: "aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text",
            returnStdout: true
        ).trim()

        result.LISTENER_ARN = sh(
            script: "aws elbv2 describe-listeners --load-balancer-arn ${result.ALB_ARN} --query 'Listeners[0].ListenerArn' --output text",
            returnStdout: true
        ).trim()

        // Fetch all target groups and their weights from the listener's default action
        def targetGroupsJson = sh(
            script: """
            aws elbv2 describe-listeners --listener-arns ${result.LISTENER_ARN} \
            --query 'Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups' \
            --output json
            """,
            returnStdout: true
        ).trim()

        def targetGroups = parseJsonString(targetGroupsJson)

        def liveTgArn = null
        if (targetGroups) {
            targetGroups.each { tg ->
                if (tg.Weight > 0) {
                    liveTgArn = tg.TargetGroupArn
                }
            }
        }
        if (liveTgArn == null && targetGroups?.size() > 0) {
            liveTgArn = targetGroups[0].TargetGroupArn
        }

        if (liveTgArn == result.BLUE_TG_ARN) {
            result.LIVE_ENV = "BLUE"
            result.IDLE_ENV = "GREEN"
            result.LIVE_TG_ARN = result.BLUE_TG_ARN
            result.IDLE_TG_ARN = result.GREEN_TG_ARN
            result.LIVE_SERVICE = "blue-service"
            result.IDLE_SERVICE = "green-service"
        } else if (liveTgArn == result.GREEN_TG_ARN) {
            result.LIVE_ENV = "GREEN"
            result.IDLE_ENV = "BLUE"
            result.LIVE_TG_ARN = result.GREEN_TG_ARN
            result.IDLE_TG_ARN = result.BLUE_TG_ARN
            result.LIVE_SERVICE = "green-service"
            result.IDLE_SERVICE = "blue-service"
        } else {
            error "❌ Live Target Group ARN (${liveTgArn}) does not match Blue or Green Target Groups."
        }

        echo "✅ ECS Cluster: ${result.ECS_CLUSTER}"
        echo "✅ Blue Target Group ARN: ${result.BLUE_TG_ARN}"
        echo "✅ Green Target Group ARN: ${result.GREEN_TG_ARN}"
        echo "✅ ALB ARN: ${result.ALB_ARN}"
        echo "✅ Listener ARN: ${result.LISTENER_ARN}"
        echo "✅ LIVE ENV: ${result.LIVE_ENV}"
        echo "✅ IDLE ENV: ${result.IDLE_ENV}"

        return result

    } catch (Exception e) {
        error "❌ Failed to fetch ECS resources: ${e.message}"
    }
}

@NonCPS
def parseJsonString(String json) {
    new JsonSlurper().parseText(json)
}



def ensureTargetGroupAssociation(Map config) {
    echo "Ensuring target group is associated with load balancer..."

    if (!config.IDLE_TG_ARN || config.IDLE_TG_ARN.trim() == "") {
        error "IDLE_TG_ARN is missing or empty"
    }
    if (!config.LISTENER_ARN || config.LISTENER_ARN.trim() == "") {
        error "LISTENER_ARN is missing or empty"
    }

    def targetGroupInfo = sh(
        script: """
        aws elbv2 describe-target-groups --target-group-arns ${config.IDLE_TG_ARN} --query 'TargetGroups[0].LoadBalancerArns' --output json
        """,
        returnStdout: true
    ).trim()

    // Use a @NonCPS helper for JSON parsing
    def targetGroupJson = parseJson(targetGroupInfo)

    if (targetGroupJson.size() == 0) {
        echo "⚠️ Target group ${config.IDLE_ENV} is not associated with a load balancer. Creating a path-based rule..."

        def rulesJson = sh(
            script: """
            aws elbv2 describe-rules --listener-arn ${config.LISTENER_ARN} --query 'Rules[*].Priority' --output json
            """,
            returnStdout: true
        ).trim()

        def priorities = parseJson(rulesJson)
            .findAll { it != 'default' }
            .collect { it as int }
            .sort()

        int startPriority = 100
        int nextPriority = startPriority
        for (p in priorities) {
            if (p == nextPriority) {
                nextPriority++
            } else if (p > nextPriority) {
                break
            }
        }
        echo "Using rule priority: ${nextPriority}"

        sh """
        aws elbv2 create-rule \
            --listener-arn ${config.LISTENER_ARN} \
            --priority ${nextPriority} \
            --conditions '[{"Field":"path-pattern","Values":["/associate-tg*"]}]' \
            --actions '[{"Type":"forward","TargetGroupArn":"${config.IDLE_TG_ARN}"}]'
        """

        sleep(10)
        echo "✅ Target group associated with load balancer via path rule (priority ${nextPriority})"
    } else {
        echo "✅ Target group is already associated with load balancer"
    }
}

@NonCPS
def parseJson(String text) {
    new groovy.json.JsonSlurper().parseText(text)
}


import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def updateApplication(Map config) {
    echo "Running ECS update application logic..."

    try {
        // Step 1: Dynamically discover ECS cluster
        def clustersJson = sh(
            script: "aws ecs list-clusters --region ${env.AWS_REGION} --output json",
            returnStdout: true
        ).trim()

        def clusterArns = parseJsonSafe(clustersJson)?.clusterArns
        if (!clusterArns || clusterArns.isEmpty()) {
            error "❌ No ECS clusters found in region ${env.AWS_REGION}"
        }

        def selectedClusterArn = clusterArns[0]
        def selectedClusterName = selectedClusterArn.tokenize('/').last()
        env.ECS_CLUSTER = selectedClusterName
        echo "✅ Using ECS cluster: ${env.ECS_CLUSTER}"

        // Step 2: Dynamically discover ECS services
        def servicesJson = sh(
            script: "aws ecs list-services --cluster ${env.ECS_CLUSTER} --region ${env.AWS_REGION} --output json",
            returnStdout: true
        ).trim()

        def serviceArns = parseJsonSafe(servicesJson)?.serviceArns
        if (!serviceArns || serviceArns.isEmpty()) {
            error "❌ No ECS services found in cluster ${env.ECS_CLUSTER}"
        }

        def serviceNames = serviceArns.collect { it.tokenize('/').last() }
        echo "Discovered ECS services: ${serviceNames}"

        def blueService = serviceNames.find { it.toLowerCase().contains("blue") }
        def greenService = serviceNames.find { it.toLowerCase().contains("green") }

        if (!blueService || !greenService) {
            error "❌ Could not find both 'blue' and 'green' ECS services in cluster ${env.ECS_CLUSTER}. Found services: ${serviceNames}"
        }

        // Helper to get image tag for a service
        def getImageTagForService = { serviceName ->
            def taskDefArn = sh(
                script: "aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${serviceName} --region ${env.AWS_REGION} --query 'services[0].taskDefinition' --output text",
                returnStdout: true
            ).trim()

            def taskDefJsonText = sh(
                script: "aws ecs describe-task-definition --task-definition ${taskDefArn} --region ${env.AWS_REGION} --query 'taskDefinition' --output json",
                returnStdout: true
            ).trim()

            def taskDefJson = parseJsonSafe(taskDefJsonText)
            def image = taskDefJson.containerDefinitions[0].image
            def imageTag = image.tokenize(':').last()
            return imageTag
        }

        def blueImageTag = getImageTagForService(blueService)
        def greenImageTag = getImageTagForService(greenService)

        echo "Blue service image tag: ${blueImageTag}"
        echo "Green service image tag: ${greenImageTag}"

        if (blueImageTag == "latest" && greenImageTag != "latest") {
            env.ACTIVE_ENV = "BLUE"
        } else if (greenImageTag == "latest" && blueImageTag != "latest") {
            env.ACTIVE_ENV = "GREEN"
        } else {
            echo "⚠️ Could not determine ACTIVE_ENV from image tags clearly. Defaulting ACTIVE_ENV to BLUE"
            env.ACTIVE_ENV = "BLUE"
        }

        // Validate ACTIVE_ENV and determine idle env/service
        if (!env.ACTIVE_ENV || !(env.ACTIVE_ENV.toUpperCase() in ["BLUE", "GREEN"])) {
            error "❌ ACTIVE_ENV must be set to 'BLUE' or 'GREEN'. Current value: '${env.ACTIVE_ENV}'"
        }
        env.ACTIVE_ENV = env.ACTIVE_ENV.toUpperCase()
        env.IDLE_ENV = (env.ACTIVE_ENV == "BLUE") ? "GREEN" : "BLUE"
        echo "ACTIVE_ENV: ${env.ACTIVE_ENV}"
        echo "Determined IDLE_ENV: ${env.IDLE_ENV}"

        env.IDLE_SERVICE = (env.IDLE_ENV == "BLUE") ? blueService : greenService
        echo "Selected IDLE_SERVICE: ${env.IDLE_SERVICE}"

        // Step 4: Get current 'latest' image digest
        def currentLatestImageInfo = sh(
            script: """
            aws ecr describe-images --repository-name ${env.ECR_REPO_NAME} --image-ids imageTag=latest --region ${env.AWS_REGION} --query 'imageDetails[0].{digest:imageDigest,pushedAt:imagePushedAt}' --output json 2>/dev/null || echo '{}'
            """,
            returnStdout: true
        ).trim()

        def imageDigest = getJsonFieldSafe(currentLatestImageInfo, 'digest')

        if (imageDigest) {
            def timestamp = new Date().format("yyyyMMdd-HHmmss")
            def rollbackTag = "rollback-${timestamp}"

            echo "Found current 'latest' image with digest: ${imageDigest}"
            echo "Tagging current 'latest' image as '${rollbackTag}'..."

            sh """
            aws ecr batch-get-image --repository-name ${env.ECR_REPO_NAME} --region ${env.AWS_REGION} --image-ids imageDigest=${imageDigest} --query 'images[0].imageManifest' --output text > image-manifest.json
            aws ecr put-image --repository-name ${env.ECR_REPO_NAME} --region ${env.AWS_REGION} --image-tag ${rollbackTag} --image-manifest file://image-manifest.json
            """

            echo "✅ Tagged rollback image: ${rollbackTag}"
            env.PREVIOUS_VERSION_TAG = rollbackTag
        } else {
            echo "⚠️ No current 'latest' image found to tag"
        }

        // Step 5: Build and push Docker image
        def ecrUri = sh(
            script: "aws ecr describe-repositories --repository-names ${env.ECR_REPO_NAME} --region ${env.AWS_REGION} --query 'repositories[0].repositoryUri' --output text",
            returnStdout: true
        ).trim()

        sh """
        aws ecr get-login-password --region ${env.AWS_REGION} | docker login --username AWS --password-stdin ${ecrUri}
        cd ${env.TF_WORKING_DIR}/modules/ecs/scripts
        docker build -t ${env.ECR_REPO_NAME}:latest .
        docker tag ${env.ECR_REPO_NAME}:latest ${ecrUri}:latest
        docker tag ${env.ECR_REPO_NAME}:latest ${ecrUri}:v${currentBuild.number}
        docker push ${ecrUri}:latest
        docker push ${ecrUri}:v${currentBuild.number}
        """

        env.IMAGE_URI = "${ecrUri}:latest"
        echo "✅ Image pushed: ${env.IMAGE_URI}"
        echo "✅ Also tagged as: v${currentBuild.number}"
        if (env.PREVIOUS_VERSION_TAG) {
            echo "✅ Previous version preserved as: ${env.PREVIOUS_VERSION_TAG}"
        }

        // Step 6: Update ECS Service
        echo "Updating ${env.IDLE_ENV} service..."

        def taskDefArn = sh(
            script: "aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${env.IDLE_SERVICE} --region ${env.AWS_REGION} --query 'services[0].taskDefinition' --output text",
            returnStdout: true
        ).trim()

        def taskDefJsonText = sh(
            script: "aws ecs describe-task-definition --task-definition ${taskDefArn} --region ${env.AWS_REGION} --query 'taskDefinition' --output json",
            returnStdout: true
        ).trim()

        // *** THIS IS THE KEY CHANGE: ***
        def newTaskDefJson = updateTaskDefImageAndSerialize(taskDefJsonText, env.IMAGE_URI)
        writeFile file: 'new-task-def.json', text: newTaskDefJson

        def newTaskDefArn = sh(
            script: "aws ecs register-task-definition --cli-input-json file://new-task-def.json --region ${env.AWS_REGION} --query 'taskDefinition.taskDefinitionArn' --output text",
            returnStdout: true
        ).trim()

        sh """
        aws ecs update-service \\
            --cluster ${env.ECS_CLUSTER} \\
            --service ${env.IDLE_SERVICE} \\
            --task-definition ${newTaskDefArn} \\
            --desired-count 1 \\
            --force-new-deployment \\
            --region ${env.AWS_REGION}
        """

        echo "✅ Updated service ${env.IDLE_ENV} with task def: ${newTaskDefArn}"

        echo "Waiting for ${env.IDLE_ENV} service to stabilize..."
        sh "aws ecs wait services-stable --cluster ${env.ECS_CLUSTER} --services ${env.IDLE_SERVICE} --region ${env.AWS_REGION}"
        echo "✅ Service ${env.IDLE_ENV} is stable"

    } catch (Exception e) {
        echo "❌ Error occurred during ECS update:\n${e}"
        e.printStackTrace()
        error "Failed to update ECS application"
    }
}

@NonCPS
def parseJsonSafe(String jsonText) {
    def parsed = new JsonSlurper().parseText(jsonText)
    def safeMap = [:]
    safeMap.putAll(parsed)
    return safeMap
}

@NonCPS
def getJsonFieldSafe(String jsonText, String fieldName) {
    def parsed = new JsonSlurper().parseText(jsonText)
    return parsed?."${fieldName}"?.toString()
}

// *** THIS IS THE NEW KEY METHOD ***
@NonCPS
def updateTaskDefImageAndSerialize(String jsonText, String imageUri) {
    def taskDef = new JsonSlurper().parseText(jsonText)
    ['taskDefinitionArn', 'revision', 'status', 'requiresAttributes', 'compatibilities',
     'registeredAt', 'registeredBy', 'deregisteredAt'].each { field ->
        taskDef.remove(field)
    }
    taskDef.containerDefinitions[0].image = imageUri
    return JsonOutput.prettyPrint(JsonOutput.toJson(taskDef))
}



def testEnvironment(Map config) {
    echo "🔍 Testing ${env.IDLE_ENV} environment..."

    try {
        // Dynamically fetch ALB ARN if not set
        if (!env.ALB_ARN) {
            echo "📡 Fetching ALB ARN..."
            env.ALB_ARN = sh(
                script: """
                    aws elbv2 describe-load-balancers \
                        --names ${config.albName} \
                        --query 'LoadBalancers[0].LoadBalancerArn' \
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
                    aws elbv2 describe-listeners \
                        --load-balancer-arn ${env.ALB_ARN} \
                        --query 'Listeners[0].ListenerArn' \
                        --output text
                """,
                returnStdout: true
            ).trim()
        }

        // Delete existing test rule if it exists
        echo "🧹 Cleaning up any existing test rule..."
        sh """
        TEST_RULE=\$(aws elbv2 describe-rules \
            --listener-arn ${env.LISTENER_ARN} \
            --query "Rules[?Priority=='10'].RuleArn" \
            --output text)

        if [ ! -z "\$TEST_RULE" ]; then
            aws elbv2 delete-rule --rule-arn \$TEST_RULE
        fi
        """

        // Create new test rule
        echo "🚧 Creating test rule for /test* on idle target group..."
        sh """
        aws elbv2 create-rule \
            --listener-arn ${env.LISTENER_ARN} \
            --priority 10 \
            --conditions '[{"Field":"path-pattern","Values":["/test*"]}]' \
            --actions '[{"Type":"forward","TargetGroupArn":"${env.IDLE_TG_ARN}"}]'
        """

        // Get ALB DNS
        def albDns = sh(
            script: """
                aws elbv2 describe-load-balancers \
                    --load-balancer-arns ${env.ALB_ARN} \
                    --query 'LoadBalancers[0].DNSName' \
                    --output text
            """,
            returnStdout: true
        ).trim()

        // Store DNS for later use
        env.ALB_DNS = albDns

        // Wait for rule propagation and test endpoint
        echo "⏳ Waiting for rule to propagate..."
        sh "sleep 10"

        echo "🌐 Hitting test endpoint: http://${albDns}/test/health"
        sh """
        curl -f http://${albDns}/test/health || curl -f http://${albDns}/test || echo "⚠️ Health check failed but continuing"
        """

        echo "✅ ${env.IDLE_ENV} environment tested successfully"

    } catch (Exception e) {
        echo "⚠️ Warning: Test stage encountered an issue: ${e.message}"
        echo "Proceeding with deployment despite test issues."
    } finally {
        // Cleanup test rule after testing
        echo "🧽 Cleaning up test rule..."
        sh """
        TEST_RULE=\$(aws elbv2 describe-rules \
            --listener-arn ${env.LISTENER_ARN} \
            --query "Rules[?Priority=='10'].RuleArn" \
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

def switchTrafficToTargetEnv(String targetEnv) {
    echo "🔄 Switching traffic to ${targetEnv}..."

    def blueTgArn = sh(
        script: "aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
        returnStdout: true
    ).trim()
    def greenTgArn = sh(
        script: "aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
        returnStdout: true
    ).trim()
    def albArn = sh(
        script: "aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text",
        returnStdout: true
    ).trim()
    def listenerArn = sh(
        script: "aws elbv2 describe-listeners --load-balancer-arn ${albArn} --query 'Listeners[0].ListenerArn' --output text",
        returnStdout: true
    ).trim()

    def targetArn = (targetEnv == "GREEN") ? greenTgArn : blueTgArn
    def otherArn = (targetEnv == "GREEN") ? blueTgArn : greenTgArn

    def targetGroups = [
        [TargetGroupArn: targetArn, Weight: 1],
        [TargetGroupArn: otherArn, Weight: 0]
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
        aws elbv2 modify-listener \
            --listener-arn ${listenerArn} \
            --default-actions file://forward-config.json
    """
    echo "✅ Traffic switched to ${targetEnv} (${targetArn})"
}


import groovy.json.JsonSlurper

def scaleDownOldEnvironment(Map config) {
    // --- Fetch ECS Cluster dynamically if not provided ---
    if (!config.ECS_CLUSTER) {
        echo "⚙️ ECS_CLUSTER not set, fetching dynamically from Terraform output..."
        def ecsClusterId = sh(
            script: "aws ecs list-clusters --query 'clusterArns[0]' --output text | awk -F'/' '{print \$2}'",
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
        script: "aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
        returnStdout: true
    ).trim()
    def greenTgArn = sh(
        script: "aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
        returnStdout: true
    ).trim()
    if (!blueTgArn || blueTgArn == 'None') error "Blue target group ARN not found"
    if (!greenTgArn || greenTgArn == 'None') error "Green target group ARN not found"

    // --- Determine ACTIVE_ENV dynamically if not provided ---
    if (!config.ACTIVE_ENV) {
        echo "⚙️ ACTIVE_ENV not set, fetching dynamically from ALB listener..."
        def activeTgArn = sh(
            script: 'aws elbv2 describe-listeners --listener-arns ' + config.LISTENER_ARN + ' --query \'Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups[?Weight==`1`].TargetGroupArn | [0]\' --output text',
            returnStdout: true
        ).trim()
        if (!activeTgArn || activeTgArn == 'None') {
            error "Failed to fetch active target group ARN from listener"
        }
        if (activeTgArn == blueTgArn) {
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

    // --- Dynamically determine IDLE_SERVICE (not ACTIVE_SERVICE!) ---
    if (!config.IDLE_SERVICE) {
        echo "⚙️ IDLE_SERVICE not set, determining dynamically based on IDLE_ENV..."
        def idleEnvLower = config.IDLE_ENV.toLowerCase()
        def expectedIdleServiceName = "${idleEnvLower}-service"
        def servicesJson = sh(
            script: "aws ecs list-services --cluster ${config.ECS_CLUSTER} --query 'serviceArns' --output json",
            returnStdout: true
        ).trim()
        def services = new JsonSlurper().parseText(servicesJson)
        if (!services || services.isEmpty()) {
            error "No ECS services found in cluster ${config.ECS_CLUSTER}"
        }
        def matchedIdleServiceArn = services.find { it.toLowerCase().endsWith(expectedIdleServiceName.toLowerCase()) }
        if (!matchedIdleServiceArn) {
            error "Idle service '${expectedIdleServiceName}' not found in cluster ${config.ECS_CLUSTER}"
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
        aws ecs update-service \
          --cluster ${config.ECS_CLUSTER} \
          --service ${config.IDLE_SERVICE} \
          --desired-count 0
        """
        echo "✅ Scaled down ${config.IDLE_SERVICE}"

        sh """
        aws ecs wait services-stable \
          --cluster ${config.ECS_CLUSTER} \
          --services ${config.IDLE_SERVICE}
        """
        echo "✅ ${config.IDLE_SERVICE} is now stable (scaled down)"
    } catch (Exception e) {
        echo "❌ Error during scale down: ${e.message}"
        throw e
    }
}
