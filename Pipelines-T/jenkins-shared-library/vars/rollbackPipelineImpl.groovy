// vars/rollbackPipelineImpl.groovy

// Implementation functions for rollbackPipeline

def initialize(Map config) {
    if (params.CONFIRM_ROLLBACK == 'NO') {
        currentBuild.result = 'ABORTED'
        error('Rollback was not confirmed - aborting pipeline')
    }
    
    echo "Starting rollback to previous version"
    currentBuild.displayName = " #${currentBuild.number} - Rollback"
    
    // Set execution type for EC2
    if (config.implementation == 'ec2') {
        env.EXECUTION_TYPE = 'ROLLBACK'
    }
}

def checkout(Map config) {
    // Only perform checkout for EC2 rollbacks
    if (config.implementation == 'ec2') {
        echo "Checking out code from repository for EC2 rollback..."
        checkout scmGit(branches: [[name: config.repoBranch ?: 'main']], 
                  extensions: [], 
                  userRemoteConfigs: [[url: config.repoUrl]])
    }
}

def fetchResources(Map config) {
    // Add appName to config if it exists
    def rollbackConfig = config.clone()
    
    if (config.implementation == 'ec2') {
        ec2RollbackUtils.fetchResources(rollbackConfig)
    } else if (config.implementation == 'ecs') {
        ecsRollbackUtils.fetchResources(rollbackConfig)
        
        // Store app-specific information from the result if available
        if (env.APP_NAME) {
            config.APP_NAME = env.APP_NAME
            config.APP_SUFFIX = env.APP_NAME.replace("app_", "")
        }
    }
}

def manualApprovalBeforeRollbackEC2(Map config) {
    if (config.implementation == 'ec2') {
        approvals.rollbackApprovalEC2(config)
    }
}

def prepareRollback(Map config) {
    // Add appName to config if it exists
    def rollbackConfig = config.clone()
    
    if (config.implementation == 'ec2') {
        ec2RollbackUtils.prepareRollback(rollbackConfig)
    } else if (config.implementation == 'ecs') {
        ecsRollbackUtils.prepareRollback(rollbackConfig)
    }
}

def testRollbackEnvironment(Map config) {
    if (config.implementation == 'ecs') {
        ecsRollbackUtils.testRollbackEnvironment(config)
    }
}

def manualApprovalBeforeRollbackECS(Map config) {
    if (config.implementation == 'ecs') {
        // Add app name to approval message
        def appInfo = config.appName ? " for ${config.appName}" : ""
        echo "Requesting approval for rollback${appInfo}..."
        approvals.rollbackApprovalECS(config)
    }
}

def executeRollback(Map config) {
    // Add appName to config if it exists
    def rollbackConfig = config.clone()
    
    if (config.implementation == 'ec2') {
        ec2RollbackUtils.executeEc2Rollback(rollbackConfig)
    } else if (config.implementation == 'ecs') {
        ecsRollbackUtils.executeEcsRollback(rollbackConfig)
    }
}

def postRollbackActions(Map config) {
    // Add appName to config if it exists
    def rollbackConfig = config.clone()
    
    if (config.implementation == 'ecs') {
        ecsRollbackUtils.postRollbackActions(rollbackConfig)
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
    
    // Checkout stage - only for EC2
    stages << stage('Checkout') {
        when {
            expression { return config.implementation == 'ec2' }
        }
        steps {
            script {
                checkout(config)
            }
        }
    }
    
    // Fetch Resources stage
    stages << stage('Fetch Resources') {
        steps {
            script {
                fetchResources(config)
            }
        }
    }
    
    // Manual Approval Before Rollback EC2 stage
    stages << stage('Manual Approval Before Rollback EC2') {
        when {
            expression { return config.implementation == 'ec2' }
        }
        steps {
            script {
                manualApprovalBeforeRollbackEC2(config)
            }
        }
    }
    
    // Prepare Rollback stage
    stages << stage('Prepare Rollback') {
        steps {
            script {
                prepareRollback(config)
            }
        }
    }
    
    // Test Rollback Environment stage
    stages << stage('Test Rollback Environment') {
        when {
            expression { config.implementation == 'ecs' }
        }
        steps {
            script {
                testRollbackEnvironment(config)
            }
        }
    }
    
    // Manual Approval Before Rollback ECS stage
    stages << stage('Manual Approval Before Rollback ECS') {
        when {
            expression { config.implementation == 'ecs' }
        }
        steps {
            script {
                manualApprovalBeforeRollbackECS(config)
            }
        }
    }
    
    // Execute Rollback stage
    stages << stage('Execute Rollback') {
        steps {
            script {
                executeRollback(config)
            }
        }
    }
    
    // Post-Rollback Actions stage
    stages << stage('Post-Rollback Actions') {
        steps {
            script {
                postRollbackActions(config)
            }
        }
    }
    
    return stages
}
