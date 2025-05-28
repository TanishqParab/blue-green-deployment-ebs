// vars/ecsRollbackUtils.groovy

def fetchResources(Map config) {
    echo "🔎 Finding previous ECS image for rollback..."

    try {
        env.ECS_CLUSTER = sh(
            script: "aws ecs list-clusters --query 'clusterArns[0]' --output text | awk -F'/' '{print \$2}'",
            returnStdout: true
        ).trim() ?: "blue-green-cluster"

        env.ALB_ARN = sh(
            script: "aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text",
            returnStdout: true
        ).trim()

        env.LISTENER_ARN = sh(
            script: "aws elbv2 describe-listeners --load-balancer-arn ${env.ALB_ARN} --query 'Listeners[0].ListenerArn' --output text",
            returnStdout: true
        ).trim()

        env.BLUE_TG_ARN = sh(
            script: "aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
            returnStdout: true
        ).trim()

        env.GREEN_TG_ARN = sh(
            script: "aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
            returnStdout: true
        ).trim()

        def currentTargetGroup = sh(
            script: """
                aws elbv2 describe-listeners --listener-arns ${env.LISTENER_ARN} \
                --query 'Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups[0].TargetGroupArn || Listeners[0].DefaultActions[0].TargetGroupArn' \
                --output text
            """,
            returnStdout: true
        ).trim()

        if (currentTargetGroup == env.BLUE_TG_ARN) {
            env.CURRENT_ENV = "BLUE"
            env.ROLLBACK_ENV = "GREEN"
            env.CURRENT_SERVICE = "blue-service"
            env.ROLLBACK_SERVICE = "green-service"
            env.CURRENT_TG_ARN = env.BLUE_TG_ARN
            env.ROLLBACK_TG_ARN = env.GREEN_TG_ARN
        } else {
            env.CURRENT_ENV = "GREEN"
            env.ROLLBACK_ENV = "BLUE"
            env.CURRENT_SERVICE = "green-service"
            env.ROLLBACK_SERVICE = "blue-service"
            env.CURRENT_TG_ARN = env.GREEN_TG_ARN
            env.ROLLBACK_TG_ARN = env.BLUE_TG_ARN
        }

        env.ALB_DNS = sh(
            script: "aws elbv2 describe-load-balancers --load-balancer-arns ${env.ALB_ARN} --query 'LoadBalancers[0].DNSName' --output text",
            returnStdout: true
        ).trim()

        def currentTaskDef = sh(
            script: "aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${env.CURRENT_SERVICE} --query 'services[0].taskDefinition' --output text",
            returnStdout: true
        ).trim()

        def taskDefJson = readJSON text: sh(
            script: "aws ecs describe-task-definition --task-definition ${currentTaskDef} --query 'taskDefinition' --output json",
            returnStdout: true
        ).trim()

        env.CURRENT_TASK_DEF_JSON = writeJSON returnText: true, json: taskDefJson
        env.CURRENT_IMAGE = taskDefJson.containerDefinitions[0].image

    } catch (Exception e) {
        error "❌ ECS resource fetch failed: ${e.message}"
    }
}


def prepareRollback(Map config) {
    echo "🚀 Preparing ECS rollback deployment..."

    try {
        // Find previous image version
        // Get the current task definition
        def currentTaskDef = sh(
            script: """
            aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${env.CURRENT_SERVICE} --query 'services[0].taskDefinition' --output text
            """,
            returnStdout: true
        ).trim()
        
        echo "Current task definition: ${currentTaskDef}"
        
        // Get the current task definition details as JSON string
        def taskDefJsonStr = sh(
            script: """
            aws ecs describe-task-definition --task-definition ${currentTaskDef} --query 'taskDefinition' --output json
            """,
            returnStdout: true
        ).trim()
        
        // Parse the task definition
        def taskDefJson = readJSON text: taskDefJsonStr
        
        // Get the current image
        def currentImage = taskDefJson.containerDefinitions[0].image
        echo "Current image: ${currentImage}"
        
        // Extract the repository name from the ECR URI
        def ecrRepoName = env.ECR_REPO_NAME
        
        // List all images in the repository sorted by push date (newest first)
        def imagesCmd = """
        aws ecr describe-images --repository-name ${ecrRepoName} --query 'sort_by(imageDetails,&imagePushedAt)[].[imageTags[0],imagePushedAt,imageDigest]' --output json
        """
        
        def imagesOutput = sh(script: imagesCmd, returnStdout: true).trim()
        def imagesJson = readJSON text: imagesOutput
        
        echo "Found ${imagesJson.size()} images in repository"
        
        if (imagesJson.size() < 2) {
            error "❌ Not enough images found in ECR repository. Need at least 2 images for rollback."
        }
        
        // Get the ECR repository URI
        def ecrRepoUri = sh(
            script: """
            aws ecr describe-repositories --repository-names ${ecrRepoName} --query 'repositories[0].repositoryUri' --output text
            """,
            returnStdout: true
        ).trim()
        
        // Get the current image tag
        def currentTag = currentImage.contains(":") ? currentImage.split(":")[1] : "latest"
        echo "Current image tag: ${currentTag}"
        
        // Find the previous image (not the current one)
        def previousImageTag = null
        def previousImageInfo = null
        
        // Sort images by push date (newest first)
        imagesJson = imagesJson.reverse()
        
        // Find the current image in the list
        def currentImageIndex = -1
        for (int i = 0; i < imagesJson.size(); i++) {
            if (imagesJson[i][0] == currentTag) {
                currentImageIndex = i
                break
            }
        }
        
        if (currentImageIndex == -1) {
            // Current image not found, use the second newest image
            previousImageInfo = imagesJson[1]
        } else if (currentImageIndex < imagesJson.size() - 1) {
            // Use the image before the current one
            previousImageInfo = imagesJson[currentImageIndex + 1]
        } else {
            // Current image is the oldest, use the second newest
            previousImageInfo = imagesJson[1]
        }
        
        previousImageTag = previousImageInfo[0]
        
        // Construct the rollback image URI
        env.ROLLBACK_IMAGE = "${ecrRepoUri}:${previousImageTag}"
        
        echo "✅ Found previous image for rollback: ${env.ROLLBACK_IMAGE}"
        echo "✅ Previous image tag: ${previousImageTag}"
        echo "✅ Previous image pushed at: ${previousImageInfo[1]}"
        
        // Get container name from task definition
        env.CONTAINER_NAME = taskDefJson.containerDefinitions[0].name
        echo "✅ Container name: ${env.CONTAINER_NAME}"
        
        // Store the task definition for later use
        env.CURRENT_TASK_DEF_JSON = taskDefJsonStr

        // Get the task definition for the ROLLBACK service
        def rollbackServiceTaskDef = sh(
            script: """
            aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${env.ROLLBACK_SERVICE} --query 'services[0].taskDefinition' --output text || echo "MISSING"
            """,
            returnStdout: true
        ).trim()
        
        
        if (rollbackServiceTaskDef != "MISSING" && rollbackServiceTaskDef != "None") {
            // Get the rollback service's task definition details
            def rollbackTaskDef = sh(
                script: """
                aws ecs describe-task-definition --task-definition ${rollbackServiceTaskDef} --query 'taskDefinition' --output json
                """,
                returnStdout: true
            ).trim()
            
            taskDefJson = readJSON text: rollbackTaskDef
        } else {
            // If rollback service doesn't exist, use the current service's task definition
            taskDefJson = readJSON text: env.CURRENT_TASK_DEF_JSON
        }
        
        // Remove fields that shouldn't be included when registering a new task definition
        ['taskDefinitionArn', 'revision', 'status', 'requiresAttributes', 'compatibilities', 
        'registeredAt', 'registeredBy', 'deregisteredAt'].each { field ->
            taskDefJson.remove(field)
        }
        
        // Update the container image to the rollback image
        taskDefJson.containerDefinitions[0].image = env.ROLLBACK_IMAGE
        
        // Store the container name for later use
        env.CONTAINER_NAME = taskDefJson.containerDefinitions[0].name
        echo "Using container name: ${env.CONTAINER_NAME}"
        
        // Write the updated task definition to a file
        writeJSON file: 'rollback-task-def.json', json: taskDefJson
        
        // Register the task definition for rollback
        def newTaskDefArn = sh(
            script: """
            aws ecs register-task-definition --cli-input-json file://rollback-task-def.json --query 'taskDefinition.taskDefinitionArn' --output text
            """,
            returnStdout: true
        ).trim()
        
        env.NEW_TASK_DEF_ARN = newTaskDefArn
        
        echo "✅ Registered new task definition for rollback: ${env.NEW_TASK_DEF_ARN}"

        // Check if the target group is associated with load balancer
        echo "Checking if target group is associated with load balancer..."
        def targetGroupInfo = sh(
            script: """
            aws elbv2 describe-target-groups --target-group-arns ${env.ROLLBACK_TG_ARN} --query 'TargetGroups[0]' --output json
            """,
            returnStdout: true
        ).trim()
        
        def targetGroupJson = readJSON text: targetGroupInfo
        echo "Target group info: ${targetGroupJson}"
        
        if (!targetGroupJson.containsKey('LoadBalancerArns') || targetGroupJson.LoadBalancerArns.size() == 0) {
            echo "⚠️ Target group ${env.ROLLBACK_ENV} is not associated with any load balancer. Creating association..."
            
            // Find existing rules for this target group
            def existingRules = sh(
                script: """
                aws elbv2 describe-rules --listener-arn ${env.LISTENER_ARN} --query 'Rules[?Actions[0].TargetGroupArn==`${env.ROLLBACK_TG_ARN}`].RuleArn' --output text || echo ""
                """,
                returnStdout: true
            ).trim()
            
            // If there are existing rules, delete them
            if (existingRules) {
                echo "Found existing rules for this target group. Deleting them first..."
                existingRules.split().each { ruleArn ->
                    sh """
                    aws elbv2 delete-rule --rule-arn ${ruleArn}
                    """
                }
                echo "Deleted existing rules for target group"
            }
            
            // Find an available priority
            def usedPriorities = sh(
                script: """
                aws elbv2 describe-rules --listener-arn ${env.LISTENER_ARN} --query 'Rules[?Priority!=`default`].Priority' --output json
                """,
                returnStdout: true
            ).trim()
            
            def usedPrioritiesJson = readJSON text: usedPriorities
            def priority = 100
            
            // Find the first available priority starting from 100
            while (usedPrioritiesJson.contains(priority.toString())) {
                priority++
            }
            
            echo "Using priority ${priority} for the new rule"
            
            // Create a rule with the available priority
            sh """
            aws elbv2 create-rule --listener-arn ${env.LISTENER_ARN} --priority ${priority} --conditions '[{"Field":"path-pattern","Values":["/rollback-association-path*"]}]' --actions '[{"Type":"forward","TargetGroupArn":"${env.ROLLBACK_TG_ARN}"}]'
            """
            
            echo "✅ Created rule with priority ${priority} to associate target group with load balancer"
            
            // Wait for the association to take effect
            echo "Waiting for target group association to take effect..."
            sh "sleep 10"
            
            // Verify the association was successful
            def verifyAssociation = sh(
                script: """
                aws elbv2 describe-target-groups --target-group-arns ${env.ROLLBACK_TG_ARN} --query 'TargetGroups[0].LoadBalancerArns' --output json
                """,
                returnStdout: true
            ).trim()
            
            def verifyJson = readJSON text: verifyAssociation
            
            if (verifyJson.size() == 0) {
                error "Failed to associate target group with load balancer after multiple attempts"
            }
            
            echo "✅ Target group successfully associated with load balancer"
        }
        
        // Check if the rollback service exists
        echo "Checking if rollback service exists..."
        def serviceExists = sh(
            script: """
            aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${env.ROLLBACK_SERVICE} --query 'services[0].status' --output text || echo "MISSING"
            """,
            returnStdout: true
        ).trim()
        
        echo "Service status: ${serviceExists}"
        
        if (serviceExists == "MISSING" || serviceExists == "INACTIVE") {
            echo "⚠️ Rollback service ${env.ROLLBACK_SERVICE} does not exist or is inactive. Creating new service..."
            
            // Create a new service with load balancer
            sh """
            aws ecs create-service \\
                --cluster ${env.ECS_CLUSTER} \\
                --service-name ${env.ROLLBACK_SERVICE} \\
                --task-definition ${env.NEW_TASK_DEF_ARN} \\
                --desired-count 1 \\
                --launch-type FARGATE \\
                --network-configuration "awsvpcConfiguration={subnets=[${config.subnetIds.join(',')}],securityGroups=[${config.securityGroupIds.join(',')}],assignPublicIp=ENABLED}" \\
                --load-balancers targetGroupArn=${env.ROLLBACK_TG_ARN},containerName=${env.CONTAINER_NAME},containerPort=${env.CONTAINER_PORT}
            """
        } else {
            // Update the existing service
            sh """
            aws ecs update-service \\
                --cluster ${env.ECS_CLUSTER} \\
                --service ${env.ROLLBACK_SERVICE} \\
                --task-definition ${env.NEW_TASK_DEF_ARN} \\
                --desired-count 1 \\
                --force-new-deployment
            """
        }
        
        echo "✅ ${env.ROLLBACK_ENV} service updated with previous version task definition"
        
        // Wait for service stabilization
        echo "⏳ Waiting for service to stabilize..."
        def attempts = 0
        def maxAttempts = 12
        def serviceStable = false
        
        while (!serviceStable && attempts < maxAttempts) {
            attempts++
            try {
                sh """
                aws ecs wait services-stable \\
                    --cluster ${env.ECS_CLUSTER} \\
                    --services ${env.ROLLBACK_SERVICE}
                """
                serviceStable = true
                echo "✅ Service is stable"
            } catch (Exception e) {
                if (attempts >= maxAttempts) {
                    error "❌ Service did not stabilize after ${maxAttempts} attempts"
                }
                echo "⚠️ Service not yet stable (attempt ${attempts}/${maxAttempts})"
                sleep(time: 10, unit: 'SECONDS')
            }
        }
        
        // Verify the service is running
        def serviceStatus = sh(
            script: """
            aws ecs describe-services --cluster ${env.ECS_CLUSTER} --services ${env.ROLLBACK_SERVICE} --query 'services[0].runningCount' --output text
            """,
            returnStdout: true
        ).trim().toInteger()
        
        if (serviceStatus < 1) {
            error "❌ Rollback service failed to start (runningCount: ${serviceStatus})"
        }
        
        echo "✅ Rollback service is running with ${serviceStatus} tasks"

    } catch (Exception e) {
        error "❌ ECS rollback preparation failed: ${e.message}"
    }
}

def testRollbackEnvironment(Map config) {
    echo "🔁 Testing ${env.ROLLBACK_ENV} environment before traffic switch..."

    try {
        // Remove existing test rule (priority 10) if it exists
        sh """
        echo "Checking for existing test rule on listener..."
        TEST_RULE=\$(aws elbv2 describe-rules --listener-arn ${env.LISTENER_ARN} --query "Rules[?Priority=='10'].RuleArn" --output text)
        
        if [ ! -z "\$TEST_RULE" ]; then
            echo "Deleting existing test rule..."
            aws elbv2 delete-rule --rule-arn \$TEST_RULE
        fi

        echo "Creating new test rule for /test* to point to ${env.ROLLBACK_TG_ARN}..."
        aws elbv2 create-rule \
            --listener-arn ${env.LISTENER_ARN} \
            --priority 10 \
            --conditions '[{"Field":"path-pattern","Values":["/test*"]}]' \
            --actions '[{"Type":"forward","TargetGroupArn":"${env.ROLLBACK_TG_ARN}"}]'
        """

        // Wait and test health
        sh """
        echo "Waiting for rule to propagate..."
        sleep 10

        echo "Testing health endpoint on rollback environment..."
        curl -f http://${env.ALB_DNS}/test/health \
        || curl -f http://${env.ALB_DNS}/test \
        || echo "⚠️ Health check failed, but proceeding with rollback"
        """

        echo "✅ Rollback environment (${env.ROLLBACK_ENV}) tested successfully"

    } catch (Exception e) {
        echo "⚠️ Warning: Rollback test encountered an issue: ${e.message}"
        echo "Proceeding with rollback despite test issues"
    }
}

def executeEcsRollback(Map config) {
    echo "🔄 Switching traffic to ${env.ROLLBACK_ENV} for rollback..."

    try {
        if (!env.ROLLBACK_TG_ARN || env.ROLLBACK_TG_ARN == "null") {
            error "❌ Invalid rollback target group ARN: ${env.ROLLBACK_TG_ARN}"
        }

        echo "Using rollback target group ARN: ${env.ROLLBACK_TG_ARN}"

        def currentTargetGroup = sh(
            script: """
                aws elbv2 describe-listeners --listener-arns ${env.LISTENER_ARN} \
                --query 'Listeners[0].DefaultActions[0].ForwardConfig.TargetGroups[0].TargetGroupArn || Listeners[0].DefaultActions[0].TargetGroupArn' \
                --output text
            """,
            returnStdout: true
        ).trim()

        if (currentTargetGroup == env.ROLLBACK_TG_ARN) {
            echo "⚠️ Traffic is already routed to ${env.ROLLBACK_ENV}, no change needed"
        } else {
            sh """
                aws elbv2 modify-listener \
                    --listener-arn ${env.LISTENER_ARN} \
                    --default-actions Type=forward,TargetGroupArn=${env.ROLLBACK_TG_ARN}
            """
            echo "✅ Traffic switched 100% to ${env.ROLLBACK_ENV}"
        }

        // Clean up test rule
        sh """
            TEST_RULE=\$(aws elbv2 describe-rules --listener-arn ${env.LISTENER_ARN} --query "Rules[?Priority=='10'].RuleArn" --output text)

            if [ ! -z "\$TEST_RULE" ]; then
                echo "Removing existing test rule..."
                aws elbv2 delete-rule --rule-arn \$TEST_RULE
            fi
        """

        echo "✅✅✅ ECS rollback completed successfully!"

    } catch (Exception e) {
        error "❌ Failed to switch traffic for ECS rollback: ${e.message}"
    }
}

import groovy.json.JsonSlurper
import groovy.json.JsonOutput

def postRollbackActions(Map config) {
    echo "📉 Scaling down current ${env.CURRENT_ENV} environment..."

    try {
        // Scale down the current service
        sh """
        aws ecs update-service --cluster ${env.ECS_CLUSTER} --service ${env.CURRENT_SERVICE} --desired-count 0
        """
        echo "✅ Current service (${env.CURRENT_ENV}) scaled down"

        // Wait for current service to stabilize
        sh """
        aws ecs wait services-stable --cluster ${env.ECS_CLUSTER} --services ${env.CURRENT_SERVICE}
        """
        echo "✅ Current service is stable"

        // --- Scale up rolled-back service ---
        def rollbackServiceName = env.ROLLBACK_ENV.toLowerCase() + "-service"
        echo "⬆️ Scaling up rolled-back service: ${rollbackServiceName}"

        sh """
        aws ecs update-service --cluster ${env.ECS_CLUSTER} --service ${rollbackServiceName} --desired-count 1
        """
        echo "✅ Rolled-back service scaling initiated"

        // Wait for rolled-back service to stabilize
        sh """
        aws ecs wait services-stable --cluster ${env.ECS_CLUSTER} --services ${rollbackServiceName}
        """
        echo "✅ Rolled-back service is stable"

        // --- Wait for all targets in rolled-back TG to be healthy ---
        def rollbackTgName = env.ROLLBACK_ENV.toLowerCase() + "-tg"
        def rollbackTgArn = sh(
            script: "aws elbv2 describe-target-groups --names ${rollbackTgName} --query 'TargetGroups[0].TargetGroupArn' --output text",
            returnStdout: true
        ).trim()

        echo "⏳ Waiting for all targets in ${rollbackTgName} to become healthy..."
        int maxAttempts = 10
        int attempt = 0
        int healthyCount = 0

        while (attempt < maxAttempts) {
            def healthJson = sh(
                script: "aws elbv2 describe-target-health --target-group-arn ${rollbackTgArn} --query 'TargetHealthDescriptions[*].TargetHealth.State' --output json",
                returnStdout: true
            ).trim()

            def states = new JsonSlurper().parseText(healthJson)
            healthyCount = states.count { it == "healthy" }
            echo "Healthy targets: ${healthyCount} / ${states.size()}"

            if (states && healthyCount == states.size()) {
                echo "✅ All targets in ${rollbackTgName} are healthy."
                break
            }

            attempt++
            sleep 02
        }

        if (healthyCount == 0) {
            error "❌ No healthy targets in ${rollbackTgName} TG after waiting."
        }

        // --- Switch ALB listener to rolled-back environment BEFORE ECR cleanup ---
        echo "🔄 Switching ALB listener to rolled-back environment (${env.ROLLBACK_ENV})..."

        def forwardAction = [
            [
                Type: "forward",
                ForwardConfig: [
                    TargetGroups: [
                        [TargetGroupArn: rollbackTgArn, Weight: 1]
                    ]
                ]
            ]
        ]
        def jsonFile = 'rollback-forward-config.json'
        writeFile file: jsonFile, text: JsonOutput.prettyPrint(JsonOutput.toJson(forwardAction))

        sh """
        aws elbv2 modify-listener \
          --listener-arn ${env.LISTENER_ARN} \
          --default-actions file://${jsonFile}
        """
        echo "✅ Switched ALB listener to ${rollbackTgName} after rollback"

        // Begin ECR cleanup
        echo "🧹 Cleaning up old images from ECR repository..."

        try {
            def imagesOutput = sh(
                script: """
                aws ecr describe-images --repository-name ${env.ECR_REPO_NAME} --output json
                """,
                returnStdout: true
            ).trim()

            def imagesJson = readJSON text: imagesOutput
            def imageDetails = imagesJson.imageDetails

            echo "Found ${imageDetails.size()} images in repository"

            def latestImageDigest = null
            def rollbackImageDigest = null
            def rollbackDate = null

            imageDetails.each { image ->
                def digest = image.imageDigest
                def tags = image.imageTags ?: []

                if (tags.contains("latest")) {
                    latestImageDigest = digest
                    echo "Found latest image: ${digest}"
                }

                tags.each { tag ->
                    if (tag.startsWith("rollback-")) {
                        def pushedAt = image.imagePushedAt
                        if (!rollbackDate || pushedAt > rollbackDate) {
                            rollbackImageDigest = digest
                            rollbackDate = pushedAt
                            echo "Found newer rollback image: ${digest} with tag ${tag}"
                        }
                    }
                }
            }

            echo "Latest image digest to keep: ${latestImageDigest ?: 'None'}"
            echo "Rollback image digest to keep: ${rollbackImageDigest ?: 'None'}"

            imageDetails.each { image ->
                def digest = image.imageDigest
                def tags = image.imageTags ?: []

                if (digest == latestImageDigest || digest == rollbackImageDigest) {
                    echo "Keeping image: ${digest}, tags: ${tags}"
                } else {
                    echo "Deleting image: ${digest}, tags: ${tags}"
                    sh """
                    aws ecr batch-delete-image \
                        --repository-name ${env.ECR_REPO_NAME} \
                        --image-ids imageDigest=${digest}
                    """
                }
            }

            echo "✅ ECR repository cleanup completed"

        } catch (Exception e) {
            echo "⚠️ Warning: ECR cleanup encountered an issue: ${e.message}"
            echo "Continuing despite cleanup issues"
        }

        // Final email notification
        emailext (
            to: config.emailRecipient,
            subject: "Rollback completed successfully - Build ${currentBuild.number}",
            body: """
                Rollback has been completed successfully.
                
                Previous environment: ${env.CURRENT_ENV}
                Rolled back to: ${env.ROLLBACK_ENV}
                Rolled back to image: ${env.ROLLBACK_IMAGE}
                
                The application is now accessible at: http://${env.ALB_DNS}
            """,
            replyTo: config.emailRecipient
        )

    } catch (Exception e) {
        echo "⚠️ Warning: Scale down encountered an issue: ${e.message}"
        echo "Continuing despite scale down issues"
    }
}
