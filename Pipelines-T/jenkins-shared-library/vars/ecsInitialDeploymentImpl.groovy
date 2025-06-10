// vars/ecsInitialDeploymentImpl.groovy

// Implementation functions for ECS initial deployment

def initialize(Map config) {
    def buildId = currentBuild.number
    echo "Current Build ID: ${buildId}"
}

def deployInitialApplication(Map config) {
    if (config.implementation == 'ecs' && params.INITIAL_DEPLOYMENT == 'YES') {
        echo "🚀 Executing initial deployment for ECS..."
        ecsInitialDeploymentUtils.deployToBlueService(config)
    } else {
        echo "⏭️ Skipping initial deployment (not ECS or INITIAL_DEPLOYMENT not set to YES)"
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
    
    // Deploy Initial Application stage
    stages << stage('Deploy Initial Application') {
        when {
            expression { config.implementation == 'ecs' && params.INITIAL_DEPLOYMENT == 'YES' }
        }
        steps {
            script {
                deployInitialApplication(config)
            }
        }
    }
    
    return stages
}
