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

def fetchResources(Map config) {
    if (config.implementation == 'ec2') {
        ec2RollbackUtils.fetchResources(config)
    } else if (config.implementation == 'ecs') {
        ecsRollbackUtils.fetchResources(config)
    }
}

def manualApprovalBeforeRollbackEC2(Map config) {
    if (config.implementation == 'ec2') {
        approvals.rollbackApprovalEC2(config)
    }
}

def prepareRollback(Map config) {
    if (config.implementation == 'ec2') {
        ec2RollbackUtils.prepareRollback(config)
    } else if (config.implementation == 'ecs') {
        ecsRollbackUtils.prepareRollback(config)
    }
}

def testRollbackEnvironment(Map config) {
    if (config.implementation == 'ecs') {
        ecsRollbackUtils.testRollbackEnvironment(config)
    }
}

def manualApprovalBeforeRollbackECS(Map config) {
    if (config.implementation == 'ecs') {
        approvals.rollbackApprovalECS(config)
    }
}

def executeRollback(Map config) {
    if (config.implementation == 'ec2') {
        ec2RollbackUtils.executeEc2Rollback(config)
    } else if (config.implementation == 'ecs') {
        ecsRollbackUtils.executeEcsRollback(config)
    }
}

def postRollbackActions(Map config) {
    if (config.implementation == 'ecs') {
        ecsRollbackUtils.postRollbackActions(config)
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