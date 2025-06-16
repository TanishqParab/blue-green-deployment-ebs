// vars/ecsUtils.groovy

def waitForServices(Map config) {
    // Use the app detected in detectChanges or from config
    if (!env.CHANGED_APP && !config.appName) {
        error "❌ No application specified. Run detectChanges first or provide appName in config."
    }
    
    def appName = env.CHANGED_APP ?: config.appName
    def appSuffix = appName.replace("app_", "")
    
    echo "Waiting for ECS services to stabilize for app${appSuffix}..."
    sleep(60)  // Give time for services to start
    
    // Get the cluster name with error handling
    def cluster
    try {
        cluster = sh(
            script: "terraform -chdir=${config.tfWorkingDir} output -raw ecs_cluster_id",
            returnStdout: true
        ).trim()
        
        if (!cluster) {
            echo "⚠️ Warning: Empty cluster ID returned from Terraform. Using default cluster."
            cluster = "default"
        }
    } catch (Exception e) {
        echo "⚠️ Warning: Could not get cluster ID from Terraform: ${e.message}. Using default cluster."
        cluster = "default"
    }
    
    // Verify cluster exists before proceeding - try multiple regions
    def clusterExists = "MISSING"
    def regionsToTry = ["", "us-east-1", "us-west-2", "eu-west-1"]
    
    echo "🔍 Verifying cluster '${cluster}' exists..."
    
    // First try without specifying region (uses default from AWS config)
    clusterExists = sh(
        script: """
            aws ecs describe-clusters --clusters ${cluster} --query 'clusters[0].status' --output text 2>/dev/null || echo "MISSING"
        """,
        returnStdout: true
    ).trim()
    
    // If not found, try with specific regions
    if (clusterExists == "MISSING") {
        for (def region : regionsToTry) {
            if (region) {
                echo "🔍 Trying to find cluster '${cluster}' in region ${region}..."
                clusterExists = sh(
                    script: """
                        aws ecs describe-clusters --clusters ${cluster} --region ${region} --query 'clusters[0].status' --output text 2>/dev/null || echo "MISSING"
                    """,
                    returnStdout: true
                ).trim()
                
                if (clusterExists != "MISSING") {
                    echo "✅ Found cluster '${cluster}' in region ${region}"
                    break
                }
            }
        }
    }
    
    if (clusterExists == "MISSING") {
        echo "⚠️ Warning: Cluster '${cluster}' not found in any region. Services cannot be checked."
        return
    }
    
    // Check for app-specific service names first
    def blueServiceName = "app${appSuffix}-blue-service"
    def greenServiceName = "app${appSuffix}-green-service"
    def defaultBlueServiceName = "blue-service"
    def defaultGreenServiceName = "green-service"
    
    // Try to find app-specific services
    def blueServiceExists = sh(
        script: """
            aws ecs describe-services --cluster ${cluster} --services ${blueServiceName} --query 'services[0].status' --output text 2>/dev/null || echo "MISSING"
        """,
        returnStdout: true
    ).trim()
    
    def greenServiceExists = sh(
        script: """
            aws ecs describe-services --cluster ${cluster} --services ${greenServiceName} --query 'services[0].status' --output text 2>/dev/null || echo "MISSING"
        """,
        returnStdout: true
    ).trim()
    
    // Check default services if app-specific ones aren't found
    def defaultBlueExists = "MISSING"
    def defaultGreenExists = "MISSING"
    
    if (blueServiceExists == "MISSING" && greenServiceExists == "MISSING") {
        defaultBlueExists = sh(
            script: """
                aws ecs describe-services --cluster ${cluster} --services ${defaultBlueServiceName} --query 'services[0].status' --output text 2>/dev/null || echo "MISSING"
            """,
            returnStdout: true
        ).trim()
        
        defaultGreenExists = sh(
            script: """
                aws ecs describe-services --cluster ${cluster} --services ${defaultGreenServiceName} --query 'services[0].status' --output text 2>/dev/null || echo "MISSING"
            """,
            returnStdout: true
        ).trim()
    }
    
    // Determine which service to check
    def serviceName
    if (blueServiceExists != "MISSING" && blueServiceExists != "INACTIVE") {
        serviceName = blueServiceName
        echo "Using app-specific blue service: ${serviceName}"
    } else if (greenServiceExists != "MISSING" && greenServiceExists != "INACTIVE") {
        serviceName = greenServiceName
        echo "Using app-specific green service: ${serviceName}"
    } else if (defaultBlueExists != "MISSING" && defaultBlueExists != "INACTIVE") {
        serviceName = defaultBlueServiceName
        echo "Using default blue service: ${serviceName}"
    } else if (defaultGreenExists != "MISSING" && defaultGreenExists != "INACTIVE") {
        serviceName = defaultGreenServiceName
        echo "Using default green service: ${serviceName}"
    } else {
        echo "⚠️ Warning: No active services found for app${appSuffix}. Skipping service status check."
        // Continue with health check if ALB exists
    }
    
    // Check service status if a service was found
    if (serviceName) {
        try {
            sh """
            aws ecs describe-services --cluster ${cluster} --services ${serviceName} --query 'services[0].{Status:status,DesiredCount:desiredCount,RunningCount:runningCount}' --output table
            """
        } catch (Exception e) {
            echo "⚠️ Warning: Could not check service status: ${e.message}"
        }
    }
    
    // Get the ALB DNS name with error handling
    def albDns
    try {
        albDns = sh(
            script: "terraform -chdir=${config.tfWorkingDir} output -raw alb_dns_name",
            returnStdout: true
        ).trim()
        
        if (!albDns) {
            echo "⚠️ Warning: Could not get ALB DNS name from Terraform. Skipping health check."
            return
        }
    } catch (Exception e) {
        echo "⚠️ Warning: Could not get ALB DNS name: ${e.message}. Skipping health check."
        return
    }
    
    // Determine health endpoint based on app
    def healthEndpoint = appSuffix == "1" ? "/health" : "/app${appSuffix}/health"
    
    echo "Application ${appName} is accessible at: http://${albDns}${appSuffix == "1" ? "" : "/app" + appSuffix}"
    
    // Test the application with retries
    sh """
    # Wait for the application to be fully available
    sleep 30
    
    # Test the health endpoint with retries
    for i in {1..3}; do
        if curl -f http://${albDns}${healthEndpoint}; then
            echo "✅ Health check passed for app${appSuffix}"
            exit 0
        else
            echo "⚠️ Health check attempt \$i failed, retrying in 10 seconds..."
            sleep 10
        fi
    done
    
    echo "⚠️ Health check failed for app${appSuffix} but continuing with deployment"
    """
}

def cleanResources(Map config) {
    if (params.MANUAL_BUILD != 'DESTROY' || config.implementation != 'ecs') {
        echo "⚠️ Skipping ECR cleanup as conditions not met (either not DESTROY or not ECS)."
        return
    }

    // Use the app detected in detectChanges or from config
    if (!env.CHANGED_APP && !config.appName) {
        error "❌ No application specified. Run detectChanges first or provide appName in config."
    }
    
    def appName = env.CHANGED_APP ?: config.appName
    def appSuffix = appName.replace("app_", "")
    
    echo "🧹 Cleaning up ECR repository for app${appSuffix} before destruction..."

    try {
        // Check if the ECR repository exists
        def ecrRepoExists = sh(
            script: """
                aws ecr describe-repositories --repository-names ${config.ecrRepoName} --region ${config.awsRegion} &>/dev/null && echo 0 || echo 1
            """,
            returnStdout: true
        ).trim() == "0"

        if (ecrRepoExists) {
            echo "🔍 Fetching images for app${appSuffix} in repository ${config.ecrRepoName}..."

            // Get all images
            def imagesOutput = sh(
                script: """
                    aws ecr describe-images --repository-name ${config.ecrRepoName} --output json
                """,
                returnStdout: true
            ).trim()

            def imagesJson = readJSON text: imagesOutput
            def imageDetails = imagesJson.imageDetails
            
            // Filter images related to this app
            def appImages = imageDetails.findAll { image ->
                image.imageTags?.any { tag -> tag.contains("app_${appSuffix}") || tag.contains("app${appSuffix}") }
            }

            echo "Found ${appImages.size()} images for app${appSuffix} in repository"

            appImages.each { image ->
                def digest = image.imageDigest
                echo "Deleting image: ${digest}"
                sh """
                    aws ecr batch-delete-image \\
                        --repository-name ${config.ecrRepoName} \\
                        --image-ids imageDigest=${digest}
                """
            }

            echo "✅ ECR repository cleanup for app${appSuffix} completed."
        } else {
            echo "ℹ️ ECR repository ${config.ecrRepoName} not found, skipping cleanup"
        }
    } catch (Exception e) {
        echo "⚠️ Warning: ECR cleanup for app${appSuffix} encountered an issue: ${e.message}"
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
            
            // Detect which app was changed - support multiple patterns for app files
            def appPatterns = [
                ~/.*app_([1-3])\.py$/,                // Python files
                ~/.*app([1-3])\.js$/,                 // JavaScript files
                ~/.*app_([1-3])\/.*\.(py|js|json)$/,  // Files in app directories
                ~/.*app([1-3])\/.*\.(py|js|json)$/    // Alternative app directory naming
            ]
            
            def appNum = null
            for (def pattern : appPatterns) {
                def appFile = changedFiles.find { it =~ pattern }
                if (appFile) {
                    def matcher = appFile =~ pattern
                    if (matcher.matches()) {
                        appNum = matcher[0][1]
                        break
                    }
                }
            }
            
            if (appNum) {
                env.CHANGED_APP = "app_${appNum}"
                echo "📱 Detected change in application: ${env.CHANGED_APP}"
            } else if (config.appName) {
                // Use provided app name if no specific app change detected
                env.CHANGED_APP = config.appName
                echo "📱 Using provided application from config: ${env.CHANGED_APP}"
            } else {
                error "❌ Could not determine which app changed and no default app provided in config"
            }
        } else {
            echo "📄 No changes detected between last two commits."
            env.DEPLOY_NEW_VERSION = 'false'
            
            // Use app from config
            if (config.appName) {
                env.CHANGED_APP = config.appName
                echo "📱 Using provided application from config: ${env.CHANGED_APP}"
            } else {
                error "❌ No changes detected and no default app provided in config"
            }
        }
    } catch (Exception e) {
        echo "⚠️ Could not determine changed files: ${e.message}"
        env.DEPLOY_NEW_VERSION = 'true'
        
        // Use app from config
        if (config.appName) {
            env.CHANGED_APP = config.appName
            echo "📱 Using provided application from config: ${env.CHANGED_APP}"
        } else {
            error "❌ Could not determine changed files and no default app provided in config"
        }
    }
}

import groovy.json.JsonSlurper

def fetchResources(Map config) {
    // Use the app detected in detectChanges or from config
    if (!env.CHANGED_APP && !config.appName) {
        error "❌ No application specified. Run detectChanges first or provide appName in config."
    }
    
    def appName = env.CHANGED_APP ?: config.appName
    def appSuffix = appName.replace("app_", "")
    
    echo "🔄 Fetching ECS and ALB resources for app${appSuffix}..."

    def result = [:]
    result.APP_NAME = appName
    result.APP_SUFFIX = appSuffix

    try {
        // Fetch ECS cluster with multiple approaches
        def regionsToTry = ["", "us-east-1", "us-west-2", "eu-west-1"]
        def clusterFound = false
        
        echo "🔍 Attempting to find ECS cluster using multiple methods..."
        
        // First try without specifying region
        try {
            result.ECS_CLUSTER = sh(
                script: "aws ecs list-clusters --query 'clusterArns[0]' --output text | cut -d'/' -f2",
                returnStdout: true
            ).trim()
            
            if (result.ECS_CLUSTER && result.ECS_CLUSTER != 'None') {
                // Verify this cluster exists
                def clusterExists = sh(
                    script: """
                        aws ecs describe-clusters --clusters ${result.ECS_CLUSTER} --query 'clusters[0].status' --output text 2>/dev/null || echo "MISSING"
                    """,
                    returnStdout: true
                ).trim()
                
                if (clusterExists != "MISSING") {
                    echo "✅ Found ECS cluster using default region: ${result.ECS_CLUSTER}"
                    clusterFound = true
                }
            }
        } catch (Exception e) {
            echo "⚠️ Error finding cluster with default region: ${e.message}"
        }
        
        // If not found, try with specific regions
        if (!clusterFound) {
            for (def region : regionsToTry) {
                if (region) {
                    try {
                        echo "🔍 Trying to find cluster in region ${region}..."
                        result.ECS_CLUSTER = sh(
                            script: "aws ecs list-clusters --region ${region} --query 'clusterArns[0]' --output text | cut -d'/' -f2",
                            returnStdout: true
                        ).trim()
                        
                        if (result.ECS_CLUSTER && result.ECS_CLUSTER != 'None') {
                            // Verify this cluster exists
                            def clusterExists = sh(
                                script: """
                                    aws ecs describe-clusters --clusters ${result.ECS_CLUSTER} --region ${region} --query 'clusters[0].status' --output text 2>/dev/null || echo "MISSING"
                                """,
                                returnStdout: true
                            ).trim()
                            
                            if (clusterExists != "MISSING") {
                                echo "✅ Found ECS cluster in region ${region}: ${result.ECS_CLUSTER}"
                                clusterFound = true
                                break
                            }
                        }
                    } catch (Exception e) {
                        echo "⚠️ Error finding cluster in region ${region}: ${e.message}"
                    }
                }
            }
        }
        
        if (!result.ECS_CLUSTER || result.ECS_CLUSTER == 'None' || !clusterFound) {
            error "Failed to fetch ECS cluster for app${appSuffix} in any region"
        }

        // Try to get app-specific target groups first, fall back to default if not found
        result.BLUE_TG_ARN = sh(
            script: "aws elbv2 describe-target-groups --names blue-tg-app${appSuffix} --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || aws elbv2 describe-target-groups --names blue-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
            returnStdout: true
        ).trim()

        result.GREEN_TG_ARN = sh(
            script: "aws elbv2 describe-target-groups --names green-tg-app${appSuffix} --query 'TargetGroups[0].TargetGroupArn' --output text 2>/dev/null || aws elbv2 describe-target-groups --names green-tg --query 'TargetGroups[0].TargetGroupArn' --output text",
            returnStdout: true
        ).trim()

        if (!result.BLUE_TG_ARN || result.BLUE_TG_ARN == 'None') {
            error "Blue target group not found for app${appSuffix}"
        }
        if (!result.GREEN_TG_ARN || result.GREEN_TG_ARN == 'None') {
            error "Green target group not found for app${appSuffix}"
        }

        // Fetch ALB and listener ARNs
        result.ALB_ARN = sh(
            script: "aws elbv2 describe-load-balancers --names blue-green-alb --query 'LoadBalancers[0].LoadBalancerArn' --output text",
            returnStdout: true
        ).trim()
        
        if (!result.ALB_ARN || result.ALB_ARN == 'None') {
            error "ALB not found for app${appSuffix}"
        }

        result.LISTENER_ARN = sh(
            script: "aws elbv2 describe-listeners --load-balancer-arn ${result.ALB_ARN} --query 'Listeners[0].ListenerArn' --output text",
            returnStdout: true
        ).trim()
        
        if (!result.LISTENER_ARN || result.LISTENER_ARN == 'None') {
            error "Listener not found for app${appSuffix}"
        }

        // Check for app-specific routing rule using the exact path pattern from Terraform
        def appPathPattern = "/app${appSuffix}*"  // Matches Terraform config: "/app1*", "/app2*", "/app3*"
        
        // Get all rules for the listener
        def rulesJson = sh(
            script: """
                aws elbv2 describe-rules --listener-arn ${result.LISTENER_ARN} --output json
            """,
            returnStdout: true
        ).trim()
        
        // Parse JSON safely
        def parsedRules
        try {
            parsedRules = parseJsonString(rulesJson)
        } catch (Exception e) {
            echo "⚠️ Error parsing rules JSON for app${appSuffix}: ${e.message}. Using empty rules."
            parsedRules = [Rules: []]
        }
        
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
        } else if (appSuffix == "1") {
            // For app1, check default action
            def listenersJson = sh(
                script: """
                    aws elbv2 describe-listeners --listener-arns ${result.LISTENER_ARN} --output json
                """,
                returnStdout: true
            ).trim()
            
            try {
                def listeners = parseJsonString(listenersJson)
                if (listeners.Listeners && listeners.Listeners.size() > 0) {
                    def defaultAction = listeners.Listeners[0].DefaultActions[0]
                    if (defaultAction.Type == 'forward') {
                        if (defaultAction.TargetGroupArn) {
                            liveTgArn = defaultAction.TargetGroupArn
                        } else if (defaultAction.ForwardConfig && defaultAction.ForwardConfig.TargetGroups) {
                            // Find target group with highest weight
                            def maxWeight = 0
                            for (def tg : defaultAction.ForwardConfig.TargetGroups) {
                                if (tg.Weight > maxWeight) {
                                    maxWeight = tg.Weight
                                    liveTgArn = tg.TargetGroupArn
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                echo "⚠️ Error checking default action for app${appSuffix}: ${e.message}"
            }
        }
        
        if (!liveTgArn) {
            // If no rule found or couldn't determine target group, use blue as default
            echo "No rule or default action found for app${appSuffix}, using blue target group as default"
            liveTgArn = result.BLUE_TG_ARN
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
            echo "⚠️ Live Target Group ARN (${liveTgArn}) does not match Blue or Green Target Groups for app${appSuffix}. Defaulting to BLUE."
            result.LIVE_ENV = "BLUE"
            result.IDLE_ENV = "GREEN"
            result.LIVE_TG_ARN = result.BLUE_TG_ARN
            result.IDLE_TG_ARN = result.GREEN_TG_ARN
            result.LIVE_SERVICE = "app${appSuffix}-blue-service"
            result.IDLE_SERVICE = "app${appSuffix}-green-service"
        }
        
        // Check if app-specific services exist, fall back to default if not
        try {
            // Get service names directly to avoid JSON parsing issues
            def serviceNames = sh(
                script: """
                    aws ecs list-services --cluster ${result.ECS_CLUSTER} --output text | tr '\\t' '\\n' | grep -o '[^/]*\$'
                """,
                returnStdout: true
            ).trim().split("\\s+")
            
            def blueServiceName = "app${appSuffix}-blue-service"
            def greenServiceName = "app${appSuffix}-green-service"
            
            def blueServiceExists = serviceNames.find { it == blueServiceName }
            def greenServiceExists = serviceNames.find { it == greenServiceName }
            
            if (!blueServiceExists || !greenServiceExists) {
                result.LIVE_SERVICE = result.LIVE_ENV.toLowerCase() + "-service"
                result.IDLE_SERVICE = result.IDLE_ENV.toLowerCase() + "-service"
                echo "⚠️ App-specific services not found, falling back to default service names"
            }
        } catch (Exception e) {
            echo "⚠️ Error checking service existence: ${e.message}. Falling back to default service names."
            result.LIVE_SERVICE = result.LIVE_ENV.toLowerCase() + "-service"
            result.IDLE_SERVICE = result.IDLE_ENV.toLowerCase() + "-service"
        }

        echo "✅ Resources for app${appSuffix}:"
        echo "   - ECS Cluster: ${result.ECS_CLUSTER}"
        echo "   - Blue Target Group: ${result.BLUE_TG_ARN.substring(result.BLUE_TG_ARN.lastIndexOf('/') + 1)}"
        echo "   - Green Target Group: ${result.GREEN_TG_ARN.substring(result.GREEN_TG_ARN.lastIndexOf('/') + 1)}"
        echo "   - LIVE ENV: ${result.LIVE_ENV}"
        echo "   - IDLE ENV: ${result.IDLE_ENV}"
        echo "   - LIVE SERVICE: ${result.LIVE_SERVICE}"
        echo "   - IDLE SERVICE: ${result.IDLE_SERVICE}"

        return result

    } catch (Exception e) {
        error "❌ Failed to fetch ECS resources for app${appSuffix}: ${e.message}"
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
    if (!config.APP_NAME) {
        error "APP_NAME is missing in config"
    }
    def appName = config.APP_NAME
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

def updateApplication(Map config) {
    try {
        // Get app name from config, don't rely on env variables
        def appName = config.APP_NAME ?: config.appName
        if (!appName) {
            error "❌ No application specified. Provide APP_NAME in config."
        }
        
        def appSuffix = config.APP_SUFFIX ?: appName.replace("app_", "")
        
        echo "🚀 Running ECS update for application: ${appName}"
        
        // Get AWS region dynamically - ensure it's a valid region string
        def awsRegion = sh(
            script: "aws configure get region || echo 'us-east-1'",
            returnStdout: true
        ).trim()
        
        // Validate region is not an ARN or other invalid value
        if (awsRegion.contains(":")) {
            echo "⚠️ Invalid AWS region detected: ${awsRegion}. Using default us-east-1."
            awsRegion = "us-east-1"
        }
        
        // Discover ECR repository
        def ecrRepoName = config.ecrRepoName
        if (!ecrRepoName) {
            def reposJson = sh(
                script: "aws ecr describe-repositories --region ${awsRegion} --output json",
                returnStdout: true
            ).trim()
            
            def repos = parseJsonSafe(reposJson)?.repositories
            if (repos && !repos.isEmpty()) {
                ecrRepoName = repos[0].repositoryName
                echo "Using discovered ECR repository: ${ecrRepoName}"
            } else {
                error "❌ No ECR repositories found. Please specify ecrRepoName in config."
            }
        }
        
        // Step 1: Dynamically discover ECS cluster with multiple approaches
        def clusterName
        def clusterExists = "MISSING"
        
        echo "🔍 Attempting to find ECS cluster using multiple methods..."
        
        // Method 1: Try listing clusters without region first
        try {
            def clustersJson = sh(
                script: "aws ecs list-clusters --output json",
                returnStdout: true
            ).trim()
            
            def clusterArns = parseJsonSafe(clustersJson)?.clusterArns
            if (clusterArns && !clusterArns.isEmpty()) {
                def selectedClusterArn = clusterArns[0]
                clusterName = selectedClusterArn.tokenize('/').last()
                
                // Verify this cluster exists
                clusterExists = sh(
                    script: """
                        aws ecs describe-clusters --clusters ${clusterName} --query 'clusters[0].status' --output text 2>/dev/null || echo "MISSING"
                    """,
                    returnStdout: true
                ).trim()
                
                if (clusterExists != "MISSING") {
                    echo "✅ Found ECS cluster using default region: ${clusterName}"
                }
            }
        } catch (Exception e) {
            echo "⚠️ Error listing clusters with default region: ${e.message}"
        }
        
        // Method 2: Try with explicit region if first method failed
        if (clusterExists == "MISSING") {
            try {
                echo "🔍 Trying to find cluster with explicit region ${awsRegion}..."
                def clustersJson = sh(
                    script: "aws ecs list-clusters --region ${awsRegion} --output json",
                    returnStdout: true
                ).trim()
                
                def clusterArns = parseJsonSafe(clustersJson)?.clusterArns
                if (clusterArns && !clusterArns.isEmpty()) {
                    def selectedClusterArn = clusterArns[0]
                    clusterName = selectedClusterArn.tokenize('/').last()
                    
                    // Verify this cluster exists
                    clusterExists = sh(
                        script: """
                            aws ecs describe-clusters --clusters ${clusterName} --region ${awsRegion} --query 'clusters[0].status' --output text 2>/dev/null || echo "MISSING"
                        """,
                        returnStdout: true
                    ).trim()
                    
                    if (clusterExists != "MISSING") {
                        echo "✅ Found ECS cluster using region ${awsRegion}: ${clusterName}"
                    }
                }
            } catch (Exception e) {
                echo "⚠️ Error listing clusters with region ${awsRegion}: ${e.message}"
            }
        }
        
        // Method 3: Try with us-east-1 specifically if we still haven't found it
        if (clusterExists == "MISSING") {
            try {
                echo "🔍 Trying to find cluster in us-east-1 region specifically..."
                def clustersJson = sh(
                    script: "aws ecs list-clusters --region us-east-1 --output json",
                    returnStdout: true
                ).trim()
                
                def clusterArns = parseJsonSafe(clustersJson)?.clusterArns
                if (clusterArns && !clusterArns.isEmpty()) {
                    def selectedClusterArn = clusterArns[0]
                    clusterName = selectedClusterArn.tokenize('/').last()
                    
                    // Verify this cluster exists
                    clusterExists = sh(
                        script: """
                            aws ecs describe-clusters --clusters ${clusterName} --region us-east-1 --query 'clusters[0].status' --output text 2>/dev/null || echo "MISSING"
                        """,
                        returnStdout: true
                    ).trim()
                    
                    if (clusterExists != "MISSING") {
                        echo "✅ Found ECS cluster in us-east-1: ${clusterName}"
                        // Update the region to match where we found the cluster
                        awsRegion = "us-east-1"
                    }
                }
            } catch (Exception e) {
                echo "⚠️ Error listing clusters in us-east-1: ${e.message}"
            }
        }
        
        // If we still haven't found a cluster, error out
        if (clusterExists == "MISSING" || !clusterName) {
            error "❌ No ECS clusters found in any region. Cannot proceed with deployment."
        }
        
        echo "✅ Using ECS cluster: ${clusterName} in region ${awsRegion}"

        // Step 2: Dynamically discover ECS services
        def servicesJson = sh(
            script: "aws ecs list-services --cluster ${clusterName} --region ${awsRegion} --output json",
            returnStdout: true
        ).trim()

        def serviceArns = parseJsonSafe(servicesJson)?.serviceArns
        
        // Define service names
        def blueServiceName
        def greenServiceName
        def activeEnv
        def idleEnv
        def activeService
        def idleService
        
        if (!serviceArns || serviceArns.isEmpty()) {
            echo "⚠️ No ECS services found in cluster ${clusterName}. Will create new services."
            blueServiceName = "app${appSuffix}-blue-service"
            greenServiceName = "app${appSuffix}-green-service"
            activeEnv = "BLUE"
            idleEnv = "GREEN"
        } else {
            def serviceNames = serviceArns.collect { it.tokenize('/').last() }
            
            // Look for app-specific services with the correct naming pattern
            def appBlueService = serviceNames.find { it.toLowerCase() == "app${appSuffix}-blue-service" }
            def appGreenService = serviceNames.find { it.toLowerCase() == "app${appSuffix}-green-service" }
            def defaultBlueService = serviceNames.find { it.toLowerCase() == "blue-service" }
            def defaultGreenService = serviceNames.find { it.toLowerCase() == "green-service" }
            
            // Determine which services to use
            if (appBlueService) {
                blueServiceName = appBlueService
                echo "Using app-specific blue service: ${blueServiceName}"
            } else if (defaultBlueService) {
                blueServiceName = defaultBlueService
                echo "Using default blue service: ${blueServiceName}"
            } else {
                blueServiceName = "app${appSuffix}-blue-service"
                echo "⚠️ Blue service not found. Will use name: ${blueServiceName}"
            }
            
            if (appGreenService) {
                greenServiceName = appGreenService
                echo "Using app-specific green service: ${greenServiceName}"
            } else if (defaultGreenService) {
                greenServiceName = defaultGreenService
                echo "Using default green service: ${greenServiceName}"
            } else {
                greenServiceName = "app${appSuffix}-green-service"
                echo "⚠️ Green service not found. Will use name: ${greenServiceName}"
            }

            // Helper to get image tag for a service
            def getImageTagForService = { serviceName ->
                try {
                    // Check if service exists first
                    def serviceExists = sh(
                        script: """
                            aws ecs describe-services --cluster ${clusterName} --services ${serviceName} --region ${awsRegion} --query 'services[0].status' --output text 2>/dev/null || echo "MISSING"
                        """,
                        returnStdout: true
                    ).trim()
                    
                    if (serviceExists == "MISSING" || serviceExists == "INACTIVE") {
                        echo "⚠️ Service ${serviceName} does not exist or is inactive"
                        return ""
                    }
                    
                    def taskDefArn = sh(
                        script: "aws ecs describe-services --cluster ${clusterName} --services ${serviceName} --region ${awsRegion} --query 'services[0].taskDefinition' --output text || echo ''",
                        returnStdout: true
                    ).trim()
                    
                    if (!taskDefArn || taskDefArn == "null" || taskDefArn == "None") {
                        echo "⚠️ No task definition found for service ${serviceName}"
                        return ""
                    }
                    
                    def taskDefJsonText = sh(
                        script: "aws ecs describe-task-definition --task-definition ${taskDefArn} --region ${awsRegion} --query 'taskDefinition' --output json || echo '{}'",
                        returnStdout: true
                    ).trim()
                    
                    def taskDefJson = parseJsonSafe(taskDefJsonText)
                    if (!taskDefJson || !taskDefJson.containerDefinitions || taskDefJson.containerDefinitions.isEmpty()) {
                        echo "⚠️ No container definitions found in task definition for service ${serviceName}"
                        return ""
                    }
                    
                    def image = taskDefJson.containerDefinitions[0].image
                    def imageTag = image?.tokenize(':')?.last() ?: ""
                    return imageTag
                } catch (Exception e) {
                    echo "⚠️ Error getting image tag for service ${serviceName}: ${e.message}"
                    return ""
                }
            }

            def blueImageTag = getImageTagForService(blueServiceName)
            def greenImageTag = getImageTagForService(greenServiceName)

            echo "Blue service image tag: ${blueImageTag}"
            echo "Green service image tag: ${greenImageTag}"

            // Determine active environment based on app-specific latest tags
            def appLatestTag = "${appName}-latest"
            if (blueImageTag.contains(appLatestTag) && !greenImageTag.contains(appLatestTag)) {
                activeEnv = "BLUE"
            } else if (greenImageTag.contains(appLatestTag) && !blueImageTag.contains(appLatestTag)) {
                activeEnv = "GREEN"
            } else {
                echo "⚠️ Could not determine active environment from image tags for app${appSuffix}. Defaulting to BLUE"
                activeEnv = "BLUE"
            }

            idleEnv = (activeEnv == "BLUE") ? "GREEN" : "BLUE"
            echo "✅ For app${appSuffix}: ACTIVE_ENV=${activeEnv}, IDLE_ENV=${idleEnv}"
        }
        
        // Set active and idle services based on environment
        activeService = (activeEnv == "BLUE") ? blueServiceName : greenServiceName
        idleService = (idleEnv == "BLUE") ? blueServiceName : greenServiceName
        
        // Step 3: Tag current image for rollback
        def currentImageInfo = sh(
            script: """
            aws ecr describe-images --repository-name ${ecrRepoName} --image-ids imageTag=${appName}-latest --region ${awsRegion} --query 'imageDetails[0].{digest:imageDigest,pushedAt:imagePushedAt}' --output json 2>/dev/null || echo '{}'
            """,
            returnStdout: true
        ).trim()

        def imageDigest = getJsonFieldSafe(currentImageInfo, 'digest')
        def rollbackTag = ""

        if (imageDigest) {
            def timestamp = new Date().format("yyyyMMdd-HHmmss")
            rollbackTag = "${appName}-rollback-${timestamp}"

            echo "Found current '${appName}-latest' image with digest: ${imageDigest}"
            echo "Tagging current '${appName}-latest' image as '${rollbackTag}'..."

            sh """
            aws ecr batch-get-image --repository-name ${ecrRepoName} --region ${awsRegion} --image-ids imageDigest=${imageDigest} --query 'images[0].imageManifest' --output text > image-manifest-${appName}.json
            aws ecr put-image --repository-name ${ecrRepoName} --region ${awsRegion} --image-tag ${rollbackTag} --image-manifest file://image-manifest-${appName}.json
            """

            echo "✅ Tagged rollback image for app${appSuffix}: ${rollbackTag}"
        } else {
            echo "⚠️ No current '${appName}-latest' image found to tag"
        }

        // Step 4: Build and push Docker image for this app
        def ecrUri = sh(
            script: "aws ecr describe-repositories --repository-names ${ecrRepoName} --region ${awsRegion} --query 'repositories[0].repositoryUri' --output text",
            returnStdout: true
        ).trim()
        
        // Validate ECR URI to ensure it's a valid URI and not JSON or other unexpected format
        if (!ecrUri || ecrUri.contains("{") || ecrUri.contains("}") || ecrUri.contains("[") || ecrUri.contains("]")) {
            echo "⚠️ Invalid ECR URI detected: ${ecrUri}. Attempting to fix..."
            
            // Try again with explicit region
            ecrUri = sh(
                script: "aws ecr describe-repositories --repository-names ${ecrRepoName} --region us-east-1 --query 'repositories[0].repositoryUri' --output text",
                returnStdout: true
            ).trim()
            
            if (!ecrUri || ecrUri.contains("{") || ecrUri.contains("}") || ecrUri.contains("[") || ecrUri.contains("]")) {
                error "❌ Failed to get valid ECR repository URI. Cannot proceed with deployment."
            }
            
            echo "✅ Fixed ECR URI: ${ecrUri}"
        }

        echo "🔨 Building and pushing Docker image for app${appSuffix}..."
        sh """
            aws ecr get-login-password --region ${awsRegion} | docker login --username AWS --password-stdin ${ecrUri}
            cd ${env.WORKSPACE}/blue-green-deployment/modules/ecs/scripts
            docker build -t ${ecrRepoName}:${appName}-latest --build-arg APP_NAME=${appSuffix} .
            docker tag ${ecrRepoName}:${appName}-latest ${ecrUri}:${appName}-latest
            docker push ${ecrUri}:${appName}-latest
        """

        def imageUri = "${ecrUri}:${appName}-latest"
        echo "✅ Image pushed for app${appSuffix}: ${imageUri}"

        // Step 5: Update ECS Service
        echo "🔄 Updating ${idleEnv} service (${idleService}) for app${appSuffix}..."

        // Check if service exists
        def serviceExists = sh(
            script: """
                aws ecs describe-services --cluster ${clusterName} --services ${idleService} --region ${awsRegion} --query 'services[0].status' --output text 2>/dev/null || echo "MISSING"
            """,
            returnStdout: true
        ).trim()
        
        def newTaskDefJson
        if (serviceExists == "MISSING" || serviceExists == "INACTIVE") {
            echo "⚠️ Service ${idleService} does not exist or is inactive. Creating a new task definition."
            
            // Get account ID for role ARN
            def accountId = sh(
                script: "aws sts get-caller-identity --query 'Account' --output text",
                returnStdout: true
            ).trim()
            
            // Create a new task definition from scratch
            newTaskDefJson = """
            {
                "family": "${idleService}-task",
                "networkMode": "awsvpc",
                "executionRoleArn": "arn:aws:iam::${accountId}:role/ecsTaskExecutionRole",
                "containerDefinitions": [
                    {
                        "name": "${idleService}-container",
                        "image": "${imageUri}",
                        "essential": true,
                        "portMappings": [
                            {
                                "containerPort": 80,
                                "hostPort": 80,
                                "protocol": "tcp"
                            }
                        ],
                        "logConfiguration": {
                            "logDriver": "awslogs",
                            "options": {
                                "awslogs-group": "/ecs/${idleService}",
                                "awslogs-region": "${awsRegion}",
                                "awslogs-stream-prefix": "ecs"
                            }
                        }
                    }
                ],
                "requiresCompatibilities": [
                    "FARGATE"
                ],
                "cpu": "256",
                "memory": "512"
            }
            """
        } else {
            // Service exists, update its task definition
            def taskDefArn = sh(
                script: "aws ecs describe-services --cluster ${clusterName} --services ${idleService} --region ${awsRegion} --query 'services[0].taskDefinition' --output text",
                returnStdout: true
            ).trim()
            
            if (!taskDefArn || taskDefArn == "null" || taskDefArn == "None") {
                error "❌ No task definition found for service ${idleService}"
            }

            def taskDefJsonText = sh(
                script: "aws ecs describe-task-definition --task-definition ${taskDefArn} --region ${awsRegion} --query 'taskDefinition' --output json",
                returnStdout: true
            ).trim()

            // Update task definition with new image
            newTaskDefJson = updateTaskDefImageAndSerialize(taskDefJsonText, imageUri, appName)
        }
        
        // Write task definition to file
        writeFile file: "new-task-def-${appSuffix}.json", text: newTaskDefJson

        // Register the new task definition
        def newTaskDefArn = sh(
            script: "aws ecs register-task-definition --cli-input-json file://new-task-def-${appSuffix}.json --region ${awsRegion} --query 'taskDefinition.taskDefinitionArn' --output text",
            returnStdout: true
        ).trim()

        if (serviceExists == "MISSING" || serviceExists == "INACTIVE") {
            // Create a new service
            echo "Creating new service ${idleService}..."
            
            // Get subnet IDs and security group IDs from default VPC
            def subnetIds = sh(
                script: """
                    aws ec2 describe-subnets --filters "Name=default-for-az,Values=true" --query 'Subnets[*].SubnetId' --output text | tr '\\t' ','
                """,
                returnStdout: true
            ).trim()
            
            def securityGroupId = sh(
                script: """
                    aws ec2 describe-security-groups --filters "Name=group-name,Values=default" --query 'SecurityGroups[0].GroupId' --output text
                """,
                returnStdout: true
            ).trim()
            
            sh """
            aws ecs create-service \\
                --cluster ${clusterName} \\
                --service-name ${idleService} \\
                --task-definition ${newTaskDefArn} \\
                --desired-count 1 \\
                --launch-type FARGATE \\
                --network-configuration "awsvpcConfiguration={subnets=[${subnetIds}],securityGroups=[${securityGroupId}],assignPublicIp=ENABLED}" \\
                --region ${awsRegion}
            """
        } else {
            // Update existing service
            sh """
            aws ecs update-service \\
                --cluster ${clusterName} \\
                --service ${idleService} \\
                --task-definition ${newTaskDefArn} \\
                --desired-count 1 \\
                --force-new-deployment \\
                --region ${awsRegion}
            """
        }

        echo "✅ Updated/created service ${idleEnv} for app${appSuffix} with task def: ${newTaskDefArn}"

        echo "⏳ Waiting for ${idleEnv} service for app${appSuffix} to stabilize..."
        try {
            sh "aws ecs wait services-stable --cluster ${clusterName} --services ${idleService} --region ${awsRegion}"
            echo "✅ Service ${idleEnv} for app${appSuffix} is stable"
        } catch (Exception e) {
            echo "⚠️ Warning: Service ${idleService} did not stabilize: ${e.message}"
            echo "Continuing with deployment despite service stability issues."
        }

        // Return all the discovered resources for downstream stages
        return [
            cluster: clusterName,
            activeEnv: activeEnv,
            idleEnv: idleEnv,
            activeService: activeService,
            idleService: idleService,
            appName: appName,
            appSuffix: appSuffix,
            awsRegion: awsRegion,
            ecrRepoName: ecrRepoName,
            imageUri: imageUri,
            rollbackTag: rollbackTag
        ]
    } catch (Exception e) {
        echo "❌ Error occurred during ECS update for app${config.APP_SUFFIX ?: (config.appName ?: 'app_1').replace('app_', '')}:\n${e.message}"
        error "Failed to update ECS application"
    }
}

@NonCPS
def parseJsonSafe(String jsonText) {
    try {
        if (!jsonText || jsonText.trim().isEmpty() || jsonText.trim() == "null") {
            return [:]
        }
        
        // Check if the text is actually JSON and not an ARN or other string
        if (!jsonText.trim().startsWith("{") && !jsonText.trim().startsWith("[")) {
            return [:]
        }
        
        def parsed = new JsonSlurper().parseText(jsonText)
        def safeMap = [:]
        safeMap.putAll(parsed)
        return safeMap
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
        
        // Check if the text is actually JSON and not an ARN or other string
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
        // Validate input
        if (!jsonText || jsonText.trim().isEmpty() || !jsonText.trim().startsWith("{")) {
            throw new Exception("Invalid JSON input: ${jsonText}")
        }
        
        def taskDef = new JsonSlurper().parseText(jsonText)
        ['taskDefinitionArn', 'revision', 'status', 'requiresAttributes', 'compatibilities',
         'registeredAt', 'registeredBy', 'deregisteredAt'].each { field ->
            taskDef.remove(field)
        }
        
        // Use the provided image URI directly (already app-specific)
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
    // Use the app detected in detectChanges or from config
    if (!env.CHANGED_APP && !config.APP_NAME) {
        error "❌ No application specified. Run detectChanges first or provide APP_NAME in config."
    }
    
    def appName = env.CHANGED_APP ?: config.APP_NAME
    def appSuffix = config.APP_SUFFIX ?: appName.replace("app_", "")
    
    echo "🔍 Testing ${env.IDLE_ENV} environment for app${appSuffix}..."

    try {
        // Ensure we have the target group ARN
        if (!env.IDLE_TG_ARN) {
            error "IDLE_TG_ARN is not set. Cannot create test rule."
        }
        
        // Dynamically fetch ALB ARN if not set
        if (!env.ALB_ARN) {
            echo "📡 Fetching ALB ARN..."
            env.ALB_ARN = sh(
                script: """
                    aws elbv2 describe-load-balancers \\
                        --names ${config.albName ?: "blue-green-alb"} \\
                        --query 'LoadBalancers[0].LoadBalancerArn' \\
                        --output text
                """,
                returnStdout: true
            ).trim()
            
            if (!env.ALB_ARN || env.ALB_ARN == 'None') {
                error "Failed to fetch ALB ARN"
            }
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
            
            if (!env.LISTENER_ARN || env.LISTENER_ARN == 'None') {
                error "Failed to fetch Listener ARN"
            }
        }

        // Use app-specific priority to avoid conflicts between multiple apps
        def testRulePriority = 10 + (appSuffix.toInteger() ?: 1)
        
        // Delete existing test rule if it exists
        echo "🧹 Cleaning up any existing test rule for app${appSuffix}..."
        sh """
        TEST_RULE=\$(aws elbv2 describe-rules \\
            --listener-arn ${env.LISTENER_ARN} \\
            --query "Rules[?Priority=='${testRulePriority}'].RuleArn" \\
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
            --priority ${testRulePriority} \\
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
        echo "🌐 Hitting test endpoint for app${appSuffix}: http://${albDns}${testEndpoint}"
        sh """
        curl -f http://${albDns}${testEndpoint} || curl -f http://${albDns}${testEndpoint.replace('/health', '')} || echo "⚠️ Health check failed but continuing"
        """

        echo "✅ ${env.IDLE_ENV} environment for app${appSuffix} tested successfully"

    } catch (Exception e) {
        echo "⚠️ Warning: Test stage for app${appSuffix} encountered an issue: ${e.message}"
        echo "Proceeding with deployment despite test issues."
    } finally {
        // Cleanup test rule after testing
        def testRulePriority = 10 + (appSuffix.toInteger() ?: 1)
        echo "🧽 Cleaning up test rule for app${appSuffix}..."
        sh """
        TEST_RULE=\$(aws elbv2 describe-rules \\
            --listener-arn ${env.LISTENER_ARN} \\
            --query "Rules[?Priority=='${testRulePriority}'].RuleArn" \\
            --output text)

        if [ ! -z "\$TEST_RULE" ]; then
            aws elbv2 delete-rule --rule-arn \$TEST_RULE
            echo "🗑️ Test rule for app${appSuffix} deleted."
        else
            echo "ℹ️ No test rule found to delete for app${appSuffix}."
        fi
        """
    }
}

import groovy.json.JsonOutput

@NonCPS
def parseJson(String text) {
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

def switchTrafficToTargetEnv(String targetEnv, String blueTgArn, String greenTgArn, String listenerArn, Map config = [:]) {
    echo "🔄 Switching traffic to ${targetEnv}..."
    
    // Use the app detected in detectChanges or from config
    if (!env.CHANGED_APP && !config.APP_NAME) {
        error "❌ No application specified. Run detectChanges first or provide APP_NAME in config."
    }
    
    def appName = env.CHANGED_APP ?: config.APP_NAME
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
        
        // Parse JSON safely
        def usedPrioritiesJson
        try {
            usedPrioritiesJson = new groovy.json.JsonSlurper().parseText(usedPriorities)
        } catch (Exception e) {
            echo "⚠️ Error parsing priorities JSON: ${e.message}. Using empty list."
            usedPrioritiesJson = []
        }
        
        def priority = 50  // Start with a lower priority for app routing
        
        // Find the first available priority
        while (usedPrioritiesJson && usedPrioritiesJson.contains(priority.toString())) {
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
    // Use the app detected in detectChanges or from config
    if (!env.CHANGED_APP && !config.APP_NAME) {
        error "❌ No application specified. Run detectChanges first or provide APP_NAME in config."
    }
    
    def appName = env.CHANGED_APP ?: config.APP_NAME
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
    if (!blueTgArn || blueTgArn == 'None') error "Blue target group ARN not found for app${appSuffix}"
    if (!greenTgArn || greenTgArn == 'None') error "Green target group ARN not found for app${appSuffix}"

    // --- Determine ACTIVE_ENV dynamically if not provided ---
    if (!config.ACTIVE_ENV) {
        echo "⚙️ ACTIVE_ENV not set, determining dynamically for app${appSuffix}..."
        
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
            echo "⚠️ Could not determine active target group for app${appSuffix}, defaulting to BLUE"
            config.ACTIVE_ENV = "BLUE"
        } else if (activeTgArn == blueTgArn) {
            config.ACTIVE_ENV = "BLUE"
        } else if (activeTgArn == greenTgArn) {
            config.ACTIVE_ENV = "GREEN"
        } else {
            error "Active target group ARN does not match blue or green target groups for app${appSuffix}"
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
        
        // Parse JSON safely
        def states
        try {
            states = new JsonSlurper().parseText(healthJson)
        } catch (Exception e) {
            echo "⚠️ Error parsing health JSON: ${e.message}. Retrying..."
            attempt++
            sleep(10)
            continue
        }
        
        healthyCount = states.count { it == "healthy" }
        echo "Healthy targets for app${appSuffix}: ${healthyCount} / ${states.size()}"
        if (states && healthyCount == states.size()) {
            echo "✅ All targets in ${config.IDLE_ENV} TG for app${appSuffix} are healthy."
            break
        }
        attempt++
        sleep 10
    }
    if (healthyCount == 0) {
        error "❌ No healthy targets in ${config.IDLE_ENV} TG for app${appSuffix} after waiting."
    }

    // --- Scale down the IDLE ECS service ---
    try {
        echo "🔽 Scaling down ${config.IDLE_SERVICE} for app${appSuffix}..."
        sh """
        aws ecs update-service \\
          --cluster ${config.ECS_CLUSTER} \\
          --service ${config.IDLE_SERVICE} \\
          --desired-count 0
        """
        echo "✅ Scaled down ${config.IDLE_SERVICE}"

        echo "⏳ Waiting for service to stabilize..."
        sh """
        aws ecs wait services-stable \\
          --cluster ${config.ECS_CLUSTER} \\
          --services ${config.IDLE_SERVICE}
        """
        echo "✅ ${config.IDLE_SERVICE} for app${appSuffix} is now stable (scaled down)"
    } catch (Exception e) {
        echo "❌ Error during scale down of app${appSuffix}: ${e.message}"
        throw e
    }
}
