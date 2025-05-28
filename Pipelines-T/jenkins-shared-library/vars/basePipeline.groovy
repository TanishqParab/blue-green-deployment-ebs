// vars/basePipeline.groovy

def call(Map config) {
    pipeline {
        agent any
        
        environment {
            AWS_REGION = "${config.awsRegion}"
            AWS_CREDENTIALS_ID = "${config.awsCredentialsId}"
            TF_WORKING_DIR = "${config.tfWorkingDir}"
            IMPLEMENTATION = "${config.implementation}"
        }
        
        parameters {
            choice(name: 'MANUAL_BUILD', choices: ['YES', 'DESTROY', 'NO'], description: 'YES: Run Terraform, DESTROY: Destroy Infra, NO: Auto Deploy App Changes')
        }

        stages {
            stage('Initialize') {
                steps {
                    script {
                        basePipelineImpl.initialize(config)
                    }
                }
            }
            
            stage('Checkout') {
                when {
                    expression { env.EXECUTION_TYPE != 'ROLLBACK' }
                }
                steps {
                    script {
                        basePipelineImpl.checkout(config)
                    }
                }
            }

            stage('Terraform Init') {
                when {
                    expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
                }
                steps {
                    script {
                        terraformInit(config)
                    }
                }
            }

            stage('Terraform Plan') {
                when {
                    expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
                }
                steps {
                    script {
                        terraformPlan(config)
                    }
                }
            }

            stage('Manual Approval') {
                when {
                    expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
                }
                steps {
                    script {
                        approvals.terraformApplyApproval(config)
                    }
                }
            }

            stage('Apply Infrastructure') {
                when {
                    expression { env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY' }
                }
                steps {
                    script {
                        terraformApply(config)
                    }
                }
            }
            
            stage('Register EC2 Instances to Target Groups') {
                when {
                    allOf {
                        expression { config.implementation == 'ec2' }
                        expression { params.MANUAL_BUILD != 'DESTROY' }
                    }
                }
                steps {
                    script {
                        ec2Utils.registerInstancesToTargetGroups(config)
                    }
                }
            }

            stage('Manual Approval for Destroy') {
                when {
                    expression { params.MANUAL_BUILD == 'DESTROY' }
                }
                steps {
                    script {
                        approvals.terraformDestroyApproval(config)
                    }
                }
            }
            
            stage('Clean Resources') {
                when {
                    expression { params.MANUAL_BUILD == 'DESTROY' && config.implementation == 'ecs' }
                }
                steps {
                    script {
                        ecsUtils.cleanResources(config)
                    }
                }
            }
            
            stage('Destroy Infrastructure') {
                when {
                    expression { params.MANUAL_BUILD == 'DESTROY' }
                }
                steps {
                    script {
                        terraformDestroy(config)
                    }
                }
            }
        }
    }
}