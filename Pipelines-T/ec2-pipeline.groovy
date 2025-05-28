// ec2-pipeline.groovy - Unified EC2 pipeline for apply, switch, and rollback operations

@Library('jenkins-shared-library-temp') _


pipeline {
    agent any
    
    parameters {
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
        IMPLEMENTATION = 'ec2'
        AWS_REGION = 'us-east-1'
        AWS_CREDENTIALS_ID = 'aws-credentials'
        SSH_KEY_ID = 'blue-green-key'
        APP_FILE = 'app.py'
        EMAIL_RECIPIENT = 'tanishqparab2001@gmail.com'
        REPO_URL = 'https://github.com/TanishqParab/blue-green-deployment-ecs-test'
        REPO_BRANCH = 'main'
        TF_WORKING_DIR = '/var/lib/jenkins/workspace/blue-green-deployment-ptest-ec2/blue-green-deployment'
    }
    
    stages {
        stage('Determine Operation') {
            steps {
                script {
                    // Determine operation - if triggered by GitHub push, use SWITCH
                    def operation = params.OPERATION ?: 'APPLY'  // Default to APPLY if null
                    if (currentBuild.getBuildCauses('hudson.triggers.SCMTrigger$SCMTriggerCause').size() > 0) {
                        echo "Build triggered by GitHub push - automatically using SWITCH operation"
                        operation = 'SWITCH'
                    } else {
                        echo "Executing EC2 ${operation} pipeline..."
                    }
                    
                    // Store the operation for later stages
                    env.SELECTED_OPERATION = operation
                }
            }
        }
        
        stage('Execute Apply') {
            when {
                expression { env.SELECTED_OPERATION == 'APPLY' }
            }
            steps {
                script {
                    // Create config map
                    def config = [
                        implementation: env.IMPLEMENTATION,
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        sshKeyId: env.SSH_KEY_ID,
                        appFile: env.APP_FILE,
                        emailRecipient: env.EMAIL_RECIPIENT,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH
                    ]
                    
                    // Call the base pipeline implementation
                    basePipelineImpl.initialize(config)
                    basePipelineImpl.checkout(config)
                    
                    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
                        terraformInit(config)
                        terraformPlan(config)
                        approvals.terraformApplyApproval(config)
                        terraformApply(config)
                    }
                    
                    if (params.MANUAL_BUILD != 'DESTROY') {
                        ec2Utils.registerInstancesToTargetGroups(config)
                    }
                    
                    if (params.MANUAL_BUILD == 'DESTROY') {
                        approvals.terraformDestroyApproval(config)
                        terraformDestroy(config)
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
                    // Create config map
                    def config = [
                        implementation: env.IMPLEMENTATION,
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        sshKeyId: env.SSH_KEY_ID,
                        appFile: env.APP_FILE,
                        emailRecipient: env.EMAIL_RECIPIENT,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH
                    ]
                    
                    // Call the switch pipeline implementation
                    switchPipelineImpl.initialize(config)
                    switchPipelineImpl.detectChanges(config)
                    
                    if (env.EXECUTION_TYPE == 'APP_DEPLOY') {
                        switchPipelineImpl.checkout(config)
                        switchPipelineImpl.fetchResources(config)
                        switchPipelineImpl.manualApprovalBeforeSwitchTrafficEC2(config)
                        switchPipelineImpl.updateApplication(config)
                        switchPipelineImpl.deployToBlueEC2Instance(config)
                        switchPipelineImpl.switchTraffic(config)
                        switchPipelineImpl.postSwitchActions(config)
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
                    // Create config map
                    def config = [
                        implementation: env.IMPLEMENTATION,
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        sshKeyId: env.SSH_KEY_ID,
                        appFile: env.APP_FILE,
                        emailRecipient: env.EMAIL_RECIPIENT,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH
                    ]
                    
                    // Call the rollback pipeline implementation
                    rollbackPipelineImpl.initialize(config)
                    rollbackPipelineImpl.fetchResources(config)
                    rollbackPipelineImpl.manualApprovalBeforeRollbackEC2(config)
                    rollbackPipelineImpl.prepareRollback(config)
                    rollbackPipelineImpl.executeRollback(config)
                    rollbackPipelineImpl.postRollbackActions(config)
                }
            }
        }
    }
}
