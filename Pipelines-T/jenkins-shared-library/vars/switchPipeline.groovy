// vars/switchPipeline.groovy

def call(Map config) {
    pipeline {
        agent any
        
        environment {
            AWS_REGION = "${config.awsRegion}"
            AWS_CREDENTIALS_ID = "${config.awsCredentialsId}"
            TF_WORKING_DIR = "${config.tfWorkingDir}"
            IMPLEMENTATION = "${config.implementation}"
            
            // EC2 specific
            SSH_KEY_ID = "${config.sshKeyId}"
            APP_FILE = "${config.appFile}"
            
            // ECS specific
            ECR_REPO_NAME = "${config.ecrRepoName}"
            CONTAINER_PORT = "${config.containerPort}"
            DOCKERFILE = "${config.dockerfile ?: 'Dockerfile'}"
        }
        
        triggers {
            githubPush()
        }

        stages {
            stage('Initialize') {
                steps {
                    script {
                        switchPipelineImpl.initialize(config)
                    }
                }
            }

            stage('Checkout') {
                when {
                    expression { 
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                        (config.implementation == 'ecs')
                    }
                }
                steps {
                    script {
                        switchPipelineImpl.checkout(config)
                    }
                }
            }
            
            stage('Detect Changes') {
                steps {
                    script {
                        switchPipelineImpl.detectChanges(config)
                    }
                }
            }
            
            stage('Fetch Resources') {
                when {
                    expression { 
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
                    }
                }
                steps {
                    script {
                        switchPipelineImpl.fetchResources(config)
                    }
                }
            }

            stage('Ensure Target Group Association') {
                when {
                    expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' }
                }
                steps {
                    script {
                        switchPipelineImpl.ensureTargetGroupAssociation(config)
                    }
                }
            }

            stage('Manual Approval Before Switch Traffic EC2') {
                when { expression { config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY' } }
                steps {
                    script {
                        switchPipelineImpl.manualApprovalBeforeSwitchTrafficEC2(config)
                    }
                }
            }
            
            stage('Update Application') {
                when {
                    expression {
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
                        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
                    }
                }
                steps {
                    script {
                        switchPipelineImpl.updateApplication(config)
                    }
                }
            }

            stage('Deploy to Blue EC2 Instance') {
                when {
                    expression { config.implementation == 'ec2' }
                }
                steps {
                    script {
                        switchPipelineImpl.deployToBlueEC2Instance(config)
                    }
                }
            }

            stage('Test Environment') {
                when {
                    expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' }
                }
                steps {
                    script {
                        switchPipelineImpl.testEnvironment(config)
                    }
                }
            }
            
            stage('Manual Approval Before Switch Traffic ECS') {
                when { 
                    expression { config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true' } 
                }
                steps {
                    script {
                        switchPipelineImpl.manualApprovalBeforeSwitchTrafficECS(config)
                    }
                }
            }
            
            stage('Switch Traffic') {
                when {
                    expression { 
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') || 
                        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
                    }
                }
                steps {
                    script {
                        switchPipelineImpl.switchTraffic(config)
                    }
                }
            }
                        
            stage('Post-Switch Actions') {
                when {
                    expression {
                        (config.implementation == 'ec2' && env.EXECUTION_TYPE == 'APP_DEPLOY') ||
                        (config.implementation == 'ecs' && env.DEPLOY_NEW_VERSION == 'true')
                    }
                }
                steps {
                    script {
                        switchPipelineImpl.postSwitchActions(config)
                    }
                }
            }
        }
    }
}