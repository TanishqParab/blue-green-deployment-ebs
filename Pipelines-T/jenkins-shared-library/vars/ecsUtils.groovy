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

def fetchResources(Map config) {
    echo "🔄 Fetching ECS and ALB resources..."

    def result = [:]

    try {
        // Use the app detected in detectChanges or from config
        def appName = env.CHANGED_APP ?: config.appName ?: "app_1"
        def appSuffix = appName.replace("app_", "")
        
        result.APP_NAME = appName
        result.APP_SUFFIX = appSuffix
        
        result.ECS_CLUSTER = sh(
            script: "aws ecs list-clusters --query 'clusterArns[0]' --output text | cut -d'/' -f2",
            returnStdout: true
        ).trim()

        // Try to get app-specific target groups with the correct naming pattern
        result.BLUE_TG_ARN = sh(
            script: "aws elbv2 describe-target-groups --names blue-tg-app${appSuffix} --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
            returnStdout: true
        ).trim()

        result.GREEN_TG_ARN = sh(
            script: "aws elbv2 describe-target-groups --names green-tg-app${appSuffix} --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
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

        // Check for app-specific routing rule using the exact path pattern from Terraform
        def appPathPattern = "/app${appSuffix}*"  // Matches Terraform config: "/app1*", "/app2*", "/app3*"
        
        // Get all rules for the listener
        def rulesJson = sh(
            script: """
                aws elbv2 describe-rules --listener-arn ${result.LISTENER_ARN} --output json
            """,
            returnStdout: true
        ).trim()
        
        // Parse JSON safely using the NonCPS method
        def parsedRules = parseJsonString(rulesJson)
        def rules = parsedRules.Rules ?: []
        def ruleArn = null
        
        // Find the rule that matches our path pattern
        for (def rule : rules) {
            if (rule.Priority != 'default' && rule.Conditions) {
                for (def condition : rule.Conditions) {
                    if (condition.Field == 'path-pattern' && condition.PathPatternConfig && condition.PathPatternConfig.Values) {
                        for (def pattern : condition.PathPatternConfig.Values) {
                            if (pattern == appPathPattern) {
                                ruleArn = rule.RuleArn
                                break
                            }
                        }
                    }
                    if (ruleArn) break
                }
            }
            if (ruleArn) break
        }
        
        echo "Looking for path pattern: ${appPathPattern}"
        echo "Found rule ARN: ${ruleArn ?: 'None'}"
        
        def liveTgArn = null
        
        if (ruleArn) {
            // Get target group from app-specific rule
            for (def rule : rules) {
                if (rule.RuleArn == ruleArn && rule.Actions && rule.Actions.size() > 0) {
                    def action = rule.Actions[0]
                    if (action.Type == 'forward') {
                        if (action.TargetGroupArn) {
                            liveTgArn = action.TargetGroupArn
                        } else if (action.ForwardConfig && action.ForwardConfig.TargetGroups) {
                            // Find target group with highest weight
                            def maxWeight = 0
                            for (def tg : action.ForwardConfig.TargetGroups) {
                                if (tg.Weight > maxWeight) {
                                    maxWeight = tg.Weight
                                    liveTgArn = tg.TargetGroupArn
                                }
                            }
                        }
                    }
                    break
                }
            }
        } else {
            // If no rule found for this app, we need to create one
            echo "No rule found for ${appPathPattern}, will need to create one"
            
            // For now, just use the blue target group as default
            liveTgArn = result.BLUE_TG_ARN
            echo "Using blue target group as default: ${liveTgArn}"
        }

        // Add this after the liveTgArn determination
        if (liveTgArn == result.BLUE_TG_ARN) {
            result.LIVE_ENV = "BLUE"
            result.IDLE_ENV = "GREEN"
            result.LIVE_TG_ARN = result.BLUE_TG_ARN
            result.IDLE_TG_ARN = result.GREEN_TG_ARN
            result.LIVE_SERVICE = "app${appSuffix}-blue-service"
            result.IDLE_SERVICE = "app${appSuffix}-green-service"
        } else if (liveTgArn == result.GREEN_TG_ARN) {
            result.LIVE_ENV = "GREEN"
            result.IDLE_ENV = "BLUE"
            result.LIVE_TG_ARN = result.GREEN_TG_ARN
            result.IDLE_TG_ARN = result.BLUE_TG_ARN
            result.LIVE_SERVICE = "app${appSuffix}-green-service"
            result.IDLE_SERVICE = "app${appSuffix}-blue-service"
        } else {
            echo "⚠️ Live Target Group ARN (${liveTgArn}) does not match Blue or Green Target Groups. Defaulting to BLUE."
            result.LIVE_ENV = "BLUE"
            result.IDLE_ENV = "GREEN"
            result.LIVE_TG_ARN = result.BLUE_TG_ARN
            result.IDLE_TG_ARN = result.GREEN_TG_ARN
            result.LIVE_SERVICE = "app${appSuffix}-blue-service"
            result.IDLE_SERVICE = "app${appSuffix}-green-service"
        }
        
        // Check if app-specific services exist, fall back to default if not
        try {
            // Use simple text output to avoid JSON parsing issues
            def blueServiceExists = sh(
                script: """
                    aws ecs list-services --cluster ${result.ECS_CLUSTER} --query "length(serviceArns[?contains(@,'app${appSuffix}-blue-service')])" --output text
                """,
                returnStdout: true
            ).trim()
            
            def greenServiceExists = sh(
                script: """
                    aws ecs list-services --cluster ${result.ECS_CLUSTER} --query "length(serviceArns[?contains(@,'app${appSuffix}-green-service')])" --output text
                """,
                returnStdout: true
            ).trim()
            
            // Check if the count is greater than 0
            if (blueServiceExists == "0" || greenServiceExists == "0") {
                result.LIVE_SERVICE = result.LIVE_ENV.toLowerCase() + "-service"
                result.IDLE_SERVICE = result.IDLE_ENV.toLowerCase() + "-service"
                echo "⚠️ App-specific services not found, falling back to default service names"
            }
        } catch (Exception e) {
            echo "⚠️ Error checking service existence: ${e.message}. Falling back to default service names."
            result.LIVE_SERVICE = result.LIVE_ENV.toLowerCase() + "-service"
            result.IDLE_SERVICE = result.IDLE_ENV.toLowerCase() + "-service"
        }

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
        error "❌ Failed to fetch ECS resources: ${e.message}"
    }
}


@NonCPS
def parseJsonString(String json) {
    try {
        if (!json || json.trim().isEmpty() || json.trim() == "null") {
            return []
        }
        
        def parsed = new JsonSlurper().parseText(json)
        
        // Handle different types of JSON responses
        if (parsed instanceof List) {
            return parsed
        } else if (parsed instanceof Map) {
            def safeMap = [:]
            safeMap.putAll(parsed)
            return safeMap
        } else {
            return parsed
        }
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


import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import com.cloudbees.groovy.cps.NonCPS

def updateApplication(Map config) {
    echo "Running ECS update application logic..."

    try {
        // Verify AWS credentials first
        echo "🔍 Verifying AWS credentials..."
        def callerIdentity = sh(
            script: "aws sts get-caller-identity --output json || echo '{}'",
            returnStdout: true
        ).trim()
        
        def identityJson = parseJsonSafe(callerIdentity)
        if (!identityJson?.Account) {
            error "❌ AWS credentials verification failed. Please check your AWS credentials configuration."
        }
        echo "✅ AWS credentials verified (Account: ${identityJson.Account})"

        // Debug statements to check input parameters
        echo "DEBUG: Received config: ${config}"
        echo "DEBUG: appName from config: ${config.appName}"

        // Use the app detected in detectChanges or from config
        def changedApp = env.CHANGED_APP ?: null
        def appName = changedApp ?: config.appName ?: config.APP_NAME ?: "app_1"
        def appSuffix = appName.replace("app_", "")
        
        echo "DEBUG: Using appName: ${appName}"
        echo "DEBUG: Using appSuffix: ${appSuffix}"
        
        echo "Updating application: ${appName}"
        
        // Step 1: Dynamically discover ECS cluster with retries and fallback
        def clusterName = config.ecsCluster ?: config.ECS_CLUSTER ?: ""
        if (!clusterName) {
            echo "⚙️ ECS_CLUSTER not set, fetching dynamically..."
            int retries = 3
            int attempt = 0
            def clusterArns = []
            
            while (attempt < retries && clusterArns.isEmpty()) {
                attempt++
                try {
                    def region = config.awsRegion ?: error("❌ awsRegion must be specified in config")
                    def clustersOutput = sh(
                        script: "aws ecs list-clusters --region ${region} --output json || echo '{}'",
                        returnStdout: true
                    ).trim()
                    
                    def clustersJson = parseJsonSafe(clustersOutput)
                    clusterArns = clustersJson?.clusterArns ?: []
                    
                    if (clusterArns.isEmpty() && attempt < retries) {
                        echo "⚠️ No clusters found (attempt ${attempt}/${retries}), retrying..."
                        sleep(time: 5, unit: 'SECONDS')
                    }
                } catch (Exception e) {
                    echo "⚠️ Error fetching clusters (attempt ${attempt}/${retries}): ${e.message}"
                    if (attempt >= retries) {
                        throw e
                    }
                    sleep(time: 5, unit: 'SECONDS')
                }
            }
            
            if (clusterArns.isEmpty()) {
                // Fallback to Terraform output
                echo "⚠️ No ECS clusters found via API after ${retries} attempts, trying Terraform output..."
                try {
                    clusterName = sh(
                        script: "terraform -chdir=${config.tfWorkingDir} output -raw ecs_cluster_id || echo ''",
                        returnStdout: true
                    ).trim()
                    
                    if (!clusterName) {
                        error "❌ No ECS clusters found in region ${config.awsRegion} and no Terraform output available"
                    }
                } catch (Exception e) {
                    error "❌ Failed to get ECS cluster from both API and Terraform: ${e.message}"
                }
            } else {
                clusterName = clusterArns[0].tokenize('/').last()
            }
        }
        
        env.ECS_CLUSTER = clusterName
        echo "✅ Using ECS cluster: ${env.ECS_CLUSTER}"

        // Continue with the rest of your update logic here...

    } catch (Exception e) {
        echo "❌ Error occurred during ECS update:\n${e}"
        e.printStackTrace()
        error "Failed to update ECS application"
    }
}

@NonCPS
def parseJsonSafe(String jsonText) {
    try {
        if (!jsonText || jsonText.trim().isEmpty() || jsonText.trim() == "null") {
            return [:]
        }
        
        // Check if the text is actually JSON and not an error message
        if (jsonText.trim().startsWith("An error occurred")) {
            echo "⚠️ AWS CLI error: ${jsonText}"
            return [:]
        }
        
        if (!jsonText.trim().startsWith("{") && !jsonText.trim().startsWith("[")) {
            return [:]
        }
        
        def parsed = new JsonSlurper().parseText(jsonText)
        if (parsed instanceof Map) {
            def safeMap = [:]
            safeMap.putAll(parsed)
            return safeMap
        } else {
            // If parsed is a List or other type, return as-is
            return parsed
        }
    } catch (Exception e) {
        echo "⚠️ Error in parseJsonSafe: ${e.message}"
        return [:]
    }
}

@NonCPS 
def getJsonFieldSafe(String jsonText, String fieldName) {
    try {
        if (!jsonText || jsonText.trim().isEmpty() || jsonText.trim() == "null") {
            return null
        }
        
        if (!jsonText.trim().startsWith("{") && !jsonText.trim().startsWith("[")) {
            return null
        }
        
        def parsed = new JsonSlurper().parseText(jsonText)
        return parsed?."${fieldName}"?.toString()
    } catch (Exception e) {
        echo "⚠️ Error in getJsonFieldSafe: ${e.message}"
        return null
    }
}

@NonCPS
def updateTaskDefImageAndSerialize(String jsonText, String imageUri, String appName) {
    try {
        if (!jsonText || jsonText.trim().isEmpty() || !jsonText.trim().startsWith("{")) {
            throw new Exception("Invalid JSON input: ${jsonText}")
        }
        
        def taskDef = new JsonSlurper().parseText(jsonText)
        ['taskDefinitionArn', 'revision', 'status', 'requiresAttributes', 'compatibilities',
         'registeredAt', 'registeredBy', 'deregisteredAt'].each { field ->
            taskDef.remove(field)
        }
        
        if (taskDef.containerDefinitions && taskDef.containerDefinitions.size() > 0) {
            taskDef.containerDefinitions[0].image = imageUri
        } else {
            throw new Exception("No container definitions found in task definition")
        }
        
        return JsonOutput.prettyPrint(JsonOutput.toJson(taskDef))
    } catch (Exception e) {
        echo "⚠️ Error in updateTaskDefImageAndSerialize: ${e.message}"
        throw e
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
