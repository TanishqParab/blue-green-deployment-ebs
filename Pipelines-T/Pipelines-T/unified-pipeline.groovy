// unified-pipeline.groovy - Unified pipeline for EC2 and ECS operations

@Library('jenkins-shared-library-temp') _

pipeline {
    agent any
    
    parameters {
        choice(
            name: 'IMPLEMENTATION',
            choices: ['ECS', 'EC2'],
            description: 'Select the implementation type: ECS or EC2'
        )
        choice(
            name: 'OPERATION',
            choices: ['APPLY', 'SWITCH', 'ROLLBACK'],
            description: 'Select the operation to perform: APPLY (deploy infrastructure), SWITCH (update and switch traffic), or ROLLBACK'
        )
        // Include parameters needed by any operation
        choice(
            name: 'MANUAL_BUILD', 
            choices: ['YES', 'DESTROY', 'NO'], 
            description: 'YES: Run Terraform, DESTROY: Destroy Infra, NO: Auto Deploy App Changes'
        )
        choice(
            name: 'CONFIRM_ROLLBACK',
            choices: ['NO', 'YES'],
            description: 'Confirm you want to rollback to previous version?'
        )
    }
    
    triggers {
        githubPush()
    }
    
    environment {
        // Common environment variables
        AWS_REGION = 'us-east-1'
        AWS_CREDENTIALS_ID = 'aws-credentials'
        REPO_URL = 'https://github.com/TanishqParab/blue-green-deployment-ecs-test'
        REPO_BRANCH = 'main'
        EMAIL_RECIPIENT = 'tanishqparab2001@gmail.com'
    }
    
    stages {
        stage('Determine Configuration') {
            steps {
                script {
                    // Determine operation - if triggered by GitHub push, use SWITCH
                    def operation = params.OPERATION ?: 'APPLY'
                    if (currentBuild.getBuildCauses('hudson.triggers.SCMTrigger$SCMTriggerCause').size() > 0) {
                        echo "Build triggered by GitHub push - automatically using SWITCH operation"
                        operation = 'SWITCH'
                    }
                    
                    // Store the operation and implementation for later stages
                    env.SELECTED_OPERATION = operation
                    env.SELECTED_IMPLEMENTATION = params.IMPLEMENTATION?.toLowerCase() ?: 'ecs'
                    
                    // Set implementation-specific working directory
                    if (env.SELECTED_IMPLEMENTATION == 'ec2') {
                        env.TF_WORKING_DIR = "/var/lib/jenkins/workspace/unified-pipeline-${env.SELECTED_IMPLEMENTATION}/blue-green-deployment"
                        env.SSH_KEY_ID = "blue-green-key"
                        env.APP_FILE = "app.py"
                    } else {
                        env.TF_WORKING_DIR = "/var/lib/jenkins/workspace/unified-pipeline-${env.SELECTED_IMPLEMENTATION}/blue-green-deployment"
                        env.ECR_REPO_NAME = "blue-green-app"
                        env.CONTAINER_NAME = "blue-green-container"
                        env.CONTAINER_PORT = "80"
                        env.DOCKERFILE = "Dockerfile"
                        env.APP_FILE = "app.py"
                    }
                    
                    echo "Executing ${env.SELECTED_IMPLEMENTATION.toUpperCase()} ${env.SELECTED_OPERATION} pipeline..."
                }
            }
        }
        
        stage('Execute Apply') {
            when {
                expression { env.SELECTED_OPERATION == 'APPLY' }
            }
            steps {
                script {
                    // Create config map based on implementation
                    def config = [
                        implementation: env.SELECTED_IMPLEMENTATION,
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH,
                        emailRecipient: env.EMAIL_RECIPIENT
                    ]
                    
                    // Add implementation-specific config
                    if (env.SELECTED_IMPLEMENTATION == 'ec2') {
                        config.sshKeyId = env.SSH_KEY_ID
                        config.appFile = env.APP_FILE
                    } else {
                        config.ecrRepoName = env.ECR_REPO_NAME
                        config.containerName = env.CONTAINER_NAME
                        config.containerPort = env.CONTAINER_PORT
                        config.dockerfile = env.DOCKERFILE
                        config.appFile = env.APP_FILE
                    }
                    
                    // Execute the stages from basePipelineImpl
                    def stages = basePipelineImpl.getStages(config)
                    for (def stageObj : stages) {
                        stage(stageObj.name) {
                            when(stageObj.when)
                            steps(stageObj.steps)
                        }
                    }
                }
            }
        }
        
        stage('Execute Switch') {
            when {
                expression { env.SELECTED_OPERATION == 'SWITCH' }
            }
            steps {
                script {
                    // Create config map based on implementation
                    def config = [
                        implementation: env.SELECTED_IMPLEMENTATION,
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH,
                        emailRecipient: env.EMAIL_RECIPIENT
                    ]
                    
                    // Add implementation-specific config
                    if (env.SELECTED_IMPLEMENTATION == 'ec2') {
                        config.sshKeyId = env.SSH_KEY_ID
                        config.appFile = env.APP_FILE
                    } else {
                        config.ecrRepoName = env.ECR_REPO_NAME
                        config.containerName = env.CONTAINER_NAME
                        config.containerPort = env.CONTAINER_PORT
                        config.dockerfile = env.DOCKERFILE
                        config.appFile = env.APP_FILE
                    }
                    
                    // Execute the stages from switchPipelineImpl
                    def stages = switchPipelineImpl.getStages(config)
                    for (def stageObj : stages) {
                        stage(stageObj.name) {
                            when(stageObj.when)
                            steps(stageObj.steps)
                        }
                    }
                }
            }
        }
        
        stage('Execute Rollback') {
            when {
                expression { env.SELECTED_OPERATION == 'ROLLBACK' }
            }
            steps {
                script {
                    // Create config map based on implementation
                    def config = [
                        implementation: env.SELECTED_IMPLEMENTATION,
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH,
                        emailRecipient: env.EMAIL_RECIPIENT
                    ]
                    
                    // Add implementation-specific config
                    if (env.SELECTED_IMPLEMENTATION == 'ec2') {
                        config.sshKeyId = env.SSH_KEY_ID
                        config.appFile = env.APP_FILE
                    } else {
                        config.ecrRepoName = env.ECR_REPO_NAME
                        config.containerName = env.CONTAINER_NAME
                        config.containerPort = env.CONTAINER_PORT
                        config.dockerfile = env.DOCKERFILE
                        config.appFile = env.APP_FILE
                    }
                    
                    // Execute the stages from rollbackPipelineImpl
                    def stages = rollbackPipelineImpl.getStages(config)
                    for (def stageObj : stages) {
                        stage(stageObj.name) {
                            when(stageObj.when)
                            steps(stageObj.steps)
                        }
                    }
                }
            }
        }
    }
}
