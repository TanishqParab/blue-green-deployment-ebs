// vars/basePipelineImpl.groovy

// Implementation functions for basePipeline

def initialize(Map config) {
    def buildId = currentBuild.number
    echo "Current Build ID: ${buildId}"
    
    // Set Execution Type
    env.EXECUTION_TYPE = 'SKIP'
    if (params.MANUAL_BUILD == 'DESTROY') {
        echo "❌ Destroy requested. Running destroy stage only."
        env.EXECUTION_TYPE = 'DESTROY'
    } else if (params.MANUAL_BUILD == 'YES') {
        echo "🛠️ Manual build requested. Running Terraform regardless of changes."
        env.EXECUTION_TYPE = 'MANUAL_APPLY'
    }
    echo "Final Execution Type: ${env.EXECUTION_TYPE}"
}

def checkout(Map config) {
    if (env.EXECUTION_TYPE != 'ROLLBACK') {
        echo "Checking out the latest code..."
        checkout scmGit(branches: [[name: config.repoBranch]], 
                      extensions: [], 
                      userRemoteConfigs: [[url: config.repoUrl]])
    }
}

def terraformInit(Map config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        terraformInit(config)
    }
}

def terraformPlan(Map config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        terraformPlan(config)
    }
}

def manualApproval(Map config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        approvals.terraformApplyApproval(config)
    }
}

def applyInfrastructure(Map config) {
    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
        terraformApply(config)
    }
}

def registerEC2Instances(Map config) {
    if (config.implementation == 'ec2' && params.MANUAL_BUILD != 'DESTROY') {
        ec2Utils.registerInstancesToTargetGroups(config)
    }
}

def manualApprovalForDestroy(Map config) {
    if (params.MANUAL_BUILD == 'DESTROY') {
        approvals.terraformDestroyApproval(config)
    }
}

def cleanResources(Map config) {
    if (params.MANUAL_BUILD == 'DESTROY' && config.implementation == 'ecs') {
        // For multi-app support, clean resources for all apps if specified
        if (params.APP_NAME == 'all') {
            echo "Cleaning resources for all apps..."
            ['app_1', 'app_2', 'app_3'].each { appName ->
                def appConfig = config.clone()
                appConfig.appName = appName
                ecsUtils.cleanResources(appConfig)
            }
        } else {
            // Clean resources for specific app
            config.appName = params.APP_NAME ?: "app_1"
            ecsUtils.cleanResources(config)
        }
    }
}

def destroyInfrastructure(Map config) {
    if (params.MANUAL_BUILD == 'DESTROY') {
        terraformDestroy(config)
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
            expression { env.EXECUTION_TYPE != 'ROLLBACK' }
        }
        steps {
            script {
                checkout(config)
            }
        }
    }
    
    // Terraform Init stage
    stages << stage('Terraform Init') {
        when {
            expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
        }
        steps {
            script {
                terraformInit(config)
            }
        }
    }
    
    // Terraform Plan stage
    stages << stage('Terraform Plan') {
        when {
            expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
        }
        steps {
            script {
                terraformPlan(config)
            }
        }
    }
    
    // Manual Approval stage
    stages << stage('Manual Approval') {
        when {
            expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
        }
        steps {
            script {
                manualApproval(config)
            }
        }
    }
    
    // Apply Infrastructure stage
    stages << stage('Apply Infrastructure') {
        when {
            expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
        }
        steps {
            script {
                applyInfrastructure(config)
            }
        }
    }
    
    // Register EC2 Instances stage
    stages << stage('Register EC2 Instances to Target Groups') {
        when {
            allOf {
                expression { config.implementation == 'ec2' }
                expression { params.MANUAL_BUILD != 'DESTROY' }
            }
        }
        steps {
            script {
                registerEC2Instances(config)
            }
        }
    }
    
    // Manual Approval for Destroy stage
    stages << stage('Manual Approval for Destroy') {
        when {
            expression { params.MANUAL_BUILD == 'DESTROY' }
        }
        steps {
            script {
                manualApprovalForDestroy(config)
            }
        }
    }
    
    // Clean Resources stage
    stages << stage('Clean Resources') {
        when {
            expression { params.MANUAL_BUILD == 'DESTROY' && config.implementation == 'ecs' }
        }
        steps {
            script {
                cleanResources(config)
            }
        }
    }
    
    // Destroy Infrastructure stage
    stages << stage('Destroy Infrastructure') {
        when {
            expression { params.MANUAL_BUILD == 'DESTROY' }
        }
        steps {
            script {
                destroyInfrastructure(config)
            }
        }
    }
    
    return stages
}