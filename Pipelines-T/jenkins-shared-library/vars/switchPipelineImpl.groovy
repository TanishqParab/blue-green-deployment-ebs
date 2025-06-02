// vars/switchPipelineImpl.groovy

// Implementation functions for switchPipeline

def initialize(Map config) {
    def buildId = currentBuild.number
    echo "Current Build ID: ${buildId}"
}

def checkout(Map config) {
    if ((config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
        (config.implementation == 'ecs')) {
        echo "Checking out the latest code..."
        checkout scmGit(branches: [[name: config.repoBranch]], 
                      extensions: [], 
                      userRemoteConfigs: [[url: config.repoUrl]])
    }
}

def detectChanges(Map config) {
    if (config.implementation == 'ec2') {
        ec2Utils.detectChanges(config)
    } else if (config.implementation == 'ecs') {
        ecsUtils.detectChanges(config)
    }
}

def fetchResources(Map config) {
    if ((config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'ec2') {
            ec2Utils.fetchResources(config)
        } else if (config.implementation == 'ecs') {
            def resourceInfo = ecsUtils.fetchResources(config)

            // Store for later use in environment variables
            env.ECS_CLUSTER     = resourceInfo.ECS_CLUSTER
            env.BLUE_TG_ARN     = resourceInfo.BLUE_TG_ARN
            env.GREEN_TG_ARN    = resourceInfo.GREEN_TG_ARN
            env.ALB_ARN         = resourceInfo.ALB_ARN
            env.LISTENER_ARN    = resourceInfo.LISTENER_ARN
            env.LIVE_ENV        = resourceInfo.LIVE_ENV
            env.IDLE_ENV        = resourceInfo.IDLE_ENV
            env.LIVE_TG_ARN     = resourceInfo.LIVE_TG_ARN
            env.IDLE_TG_ARN     = resourceInfo.IDLE_TG_ARN
            env.LIVE_SERVICE    = resourceInfo.LIVE_SERVICE
            env.IDLE_SERVICE    = resourceInfo.IDLE_SERVICE

            // CRITICAL: Set the target environment for traffic switch
            env.TARGET_ENV = resourceInfo.IDLE_ENV

            // Optionally update config for downstream stages
            config.ECS_CLUSTER  = env.ECS_CLUSTER
            config.BLUE_TG_ARN  = env.BLUE_TG_ARN
            config.GREEN_TG_ARN = env.GREEN_TG_ARN
            config.ALB_ARN      = env.ALB_ARN
            config.LISTENER_ARN = env.LISTENER_ARN
            config.LIVE_ENV     = env.LIVE_ENV
            config.IDLE_ENV     = env.IDLE_ENV
            config.LIVE_TG_ARN  = env.LIVE_TG_ARN
            config.IDLE_TG_ARN  = env.IDLE_TG_ARN
            config.LIVE_SERVICE = env.LIVE_SERVICE
            config.IDLE_SERVICE = env.IDLE_SERVICE
        }
    }
}


def ensureTargetGroupAssociation(Map config) {
    if (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true') {
        // Pass the updated config with required parameters explicitly
        ecsUtils.ensureTargetGroupAssociation([
            IDLE_TG_ARN: config.IDLE_TG_ARN,
            LISTENER_ARN: config.LISTENER_ARN,
            IDLE_ENV: config.IDLE_ENV
        ])
    }
}

def manualApprovalBeforeSwitchTrafficEC2(Map config) {
    if (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') {
        approvals.switchTrafficApprovalEC2(config)
    }
}

def updateApplication(Map config) {
    if ((config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'ec2') {
            echo "🔄 Updating application on EC2..."
            ec2Utils.updateApplication(config)
        } else if (config.implementation == 'ecs') {
            echo "🔄 Updating application on ECS..."

            // Run ECS update logic (discover ECS cluster, build & push image, update idle service)
            ecsUtils.updateApplication(config)

            // Dynamically set config values from environment
            config.ecsCluster        = env.ECS_CLUSTER ?: ''
            config.rollbackVersionTag = env.PREVIOUS_VERSION_TAG ?: ''
            config.newImageUri       = env.IMAGE_URI ?: ''
            config.activeEnv         = env.ACTIVE_ENV ?: ''
            config.idleEnv           = env.IDLE_ENV ?: ''
            config.idleService       = env.IDLE_SERVICE ?: ''

            echo """
            ✅ ECS Application Update Summary:
            ----------------------------------
            🧱 ECS Cluster        : ${config.ecsCluster}
            🔵 Active Environment : ${config.activeEnv}
            🟢 Idle Environment   : ${config.idleEnv}
            ⚙️  Idle Service       : ${config.idleService}
            🔁 Rollback Version   : ${config.rollbackVersionTag}
            🚀 New Image URI      : ${config.newImageUri}
            """
        } else {
            error "❌ Unsupported implementation type: ${config.implementation}"
        }
    }
}

def deployToBlueEC2Instance(Map config) {
    if (config.implementation == 'ec2') {
        // Prepare the config parameters needed by deployToBlueInstance
        def deployConfig = [
            albName: config.albName ?: 'blue-green-alb',                
            blueTargetGroupName: config.blueTargetGroupName ?: 'blue-tg', 
            blueTag: config.blueTag ?: 'Blue-Instance'              
        ]
        ec2Utils.deployToBlueInstance(deployConfig)
    }
}

def testEnvironment(Map config) {
    if (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true') {
        // Set ALB name dynamically or use a predefined one
        config.albName = env.CUSTOM_ALB_NAME ?: 'blue-green-alb' 

        ecsUtils.testEnvironment(config)
    }
}

def manualApprovalBeforeSwitchTrafficECS(Map config) {
    if (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true') {
        echo """
        🟡 Awaiting Manual Approval to Switch Traffic in ECS
        ------------------------------------------------------
        🔁 Rollback Version Tag : ${config.rollbackVersionTag}
        🚀 New Image URI        : ${config.newImageUri}
        📦 ECS Cluster          : ${config.ecsCluster}
        🔵 Active Environment   : ${config.activeEnv}
        🟢 Idle Environment     : ${config.idleEnv}
        ⚙️  Idle Service         : ${config.idleService}
        """

        approvals.switchTrafficApprovalECS(config)
    }
}

def switchTraffic(Map config) {
    if ((config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'ec2') {
            ec2Utils.switchTraffic(config)
        } else if (config.implementation == 'ecs') {
            ecsUtils.switchTrafficToTargetEnv(
                env.TARGET_ENV,
                env.BLUE_TG_ARN,
                env.GREEN_TG_ARN,
                env.LISTENER_ARN
            )
        }
    }
}


def postSwitchActions(Map config) {
    if ((config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')) {
        
        if (config.implementation == 'ec2') {
            // Call shared library method with parameters
            ec2Utils.tagSwapInstances([
                blueTag : 'Blue-Instance',
                greenTag: 'Green-Instance'
            ])
        } else if (config.implementation == 'ecs') {
            // Pass ALB name or other minimal config needed by scaleDownOldEnvironment
            ecsUtils.scaleDownOldEnvironment([
                ALB_NAME: config.ALB_NAME ?: 'blue-green-alb'  // Replace or set in config
            ])
        }
    }
}

// Return all the stages for the pipeline
def getStages(Map config) {
    def stages = []
    
    // Initialize stage
    stages << stage('Initialize') {
        steps {
            script {
                initialize(config)
            }
        }
    }
    
    // Checkout stage
    stages << stage('Checkout') {
        when {
            expression { 
                (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                (config.implementation == 'ecs')
            }
        }
        steps {
            script {
                checkout(config)
            }
        }
    }
    
    // Detect Changes stage
    stages << stage('Detect Changes') {
        steps {
            script {
                detectChanges(config)
            }
        }
    }
    
    // Fetch Resources stage
    stages << stage('Fetch Resources') {
        when {
            expression { 
                (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
            }
        }
        steps {
            script {
                fetchResources(config)
            }
        }
    }
    
    // Ensure Target Group Association stage
    stages << stage('Ensure Target Group Association') {
        when {
            expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' }
        }
        steps {
            script {
                ensureTargetGroupAssociation(config)
            }
        }
    }
    
    // Manual Approval Before Switch Traffic EC2 stage
    stages << stage('Manual Approval Before Switch Traffic EC2') {
        when { 
            expression { config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY' } 
        }
        steps {
            script {
                manualApprovalBeforeSwitchTrafficEC2(config)
            }
        }
    }
    
    // Update Application stage
    stages << stage('Update Application') {
        when {
            expression {
                (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
                (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
            }
        }
        steps {
            script {
                updateApplication(config)
            }
        }
    }
    
    // Deploy to Blue EC2 Instance stage
    stages << stage('Deploy to Blue EC2 Instance') {
        when {
            expression { config.implementation == 'ec2' }
        }
        steps {
            script {
                deployToBlueEC2Instance(config)
            }
        }
    }
    
    // Test Environment stage
    stages << stage('Test Environment') {
        when {
            expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' }
        }
        steps {
            script {
                testEnvironment(config)
            }
        }
    }
    
    // Manual Approval Before Switch Traffic ECS stage
    stages << stage('Manual Approval Before Switch Traffic ECS') {
        when { 
            expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' } 
        }
        steps {
            script {
                manualApprovalBeforeSwitchTrafficECS(config)
            }
        }
    }
    
    // Switch Traffic stage
    stages << stage('Switch Traffic') {
        when {
            expression { 
                (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
            }
        }
        steps {
            script {
                switchTraffic(config)
            }
        }
    }
    
    // Post-Switch Actions stage
    stages << stage('Post-Switch Actions') {
        when {
            expression {
                (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
                (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
            }
        }
        steps {
            script {
                postSwitchActions(config)
            }
        }
    }
    
    return stages
}
