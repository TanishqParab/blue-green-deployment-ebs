// vars/rollbackPipeline.groovy

def call(Map config) {
    pipeline {
        agent any
        
        parameters {
            choice(
                name: 'CONFIRM_ROLLBACK',
                choices: ['NO', 'YES'],
                description: 'Confirm you want to rollback to previous version?'
            )
        }
        
        environment {
            AWS_REGION = "${config.awsRegion ?: 'us-east-1'}"
            AWS_CREDENTIALS_ID = "${config.awsCredentialsId ?: 'aws-credentials'}"
            TF_WORKING_DIR = "${config.tfWorkingDir}"
            IMPLEMENTATION = "${config.implementation}"
            
            // EC2 specific
            SSH_KEY_ID = "${config.sshKeyId}"
            APP_FILE = "${config.appFile}"
            
            // ECS specific
            ECR_REPO_NAME = "${config.ecrRepoName}"
            CONTAINER_NAME = "${config.containerName ?: 'blue-green-container'}"
            CONTAINER_PORT = "${config.containerPort ?: '80'}"
        }

        stages {
            stage('Initialize') {
                steps {
                    script {
                        rollbackPipelineImpl.initialize(config)
                    }
                }
            }
            
            stage('Fetch Resources') {
                steps {
                    script {
                        rollbackPipelineImpl.fetchResources(config)
                    }
                }
            }

            stage('Manual Approval Before Rollback EC2') {
                when {
                    expression { return config.implementation == 'ec2' }
                }
                steps {
                    script {
                        rollbackPipelineImpl.manualApprovalBeforeRollbackEC2(config)
                    }
                }
            }

            stage('Prepare Rollback') {
                steps {
                    script {
                        rollbackPipelineImpl.prepareRollback(config)
                    }
                }
            }
             
            stage('Test Rollback Environment') {
                when {
                    expression { config.implementation == 'ecs' }
                }
                steps {
                    script {
                        rollbackPipelineImpl.testRollbackEnvironment(config)
                    }
                }
            }
            
            stage('Manual Approval Before Rollback ECS') {
                when {
                    expression { config.implementation == 'ecs' }
                }
                steps {
                    script {
                        rollbackPipelineImpl.manualApprovalBeforeRollbackECS(config)
                    }
                }
            }
            
            stage('Execute Rollback') {
                steps {
                    script {
                        rollbackPipelineImpl.executeRollback(config)
                    }
                }
            }
            
            stage('Post-Rollback Actions') {
                steps {
                    script {
                        rollbackPipelineImpl.postRollbackActions(config)
                    }
                }
            }
        }
    }
}