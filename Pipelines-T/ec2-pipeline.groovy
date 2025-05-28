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
        TF_WORKING_DIR = '/var/lib/jenkins/workspace/EC2-Unified-Pipeline/blue-green-deployment'
    }
    
    stages {
        stage('Determine Operation') {
            steps {
                script {
                    // Determine operation - if triggered by GitHub push, use SWITCH
                    def operation = params.OPERATION ?: 'APPLY'  // Default to APPLY if null
                    
                    // Check for GitHub push trigger using multiple possible cause classes
                    def isGitHubPush = false
                    def causes = currentBuild.getBuildCauses()
                    causes.each { cause ->
                        if (cause._class?.contains('SCMTrigger') || 
                            cause._class?.contains('GitHubPush') || 
                            cause.shortDescription?.contains('push') ||
                            cause.shortDescription?.contains('SCM')) {
                            isGitHubPush = true
                        }
                    }
                    
                    if (isGitHubPush) {
                        echo "Build triggered by GitHub push - automatically using SWITCH operation"
                        operation = 'SWITCH'
                    } else {
                        echo "Executing EC2 ${operation} pipeline..."
                    }
                    
                    // Store the operation for later stages
                    env.SELECTED_OPERATION = operation
                    
                    // Force implementation to be 'ec2'
                    env.IMPLEMENTATION = 'ec2'
                    env.SELECTED_IMPLEMENTATION = 'ec2'
                    echo "DEBUG: Forced implementation to EC2"
                }
            }
        }
        
        stage('Execute Apply') {
            when {
                expression { env.SELECTED_OPERATION == 'APPLY' }
            }
            steps {
                script {
                    // Force implementation to be 'ec2' again
                    env.IMPLEMENTATION = 'ec2'
                    env.SELECTED_IMPLEMENTATION = 'ec2'
                    echo "DEBUG: Environment variables - IMPLEMENTATION: ${env.IMPLEMENTATION}, SELECTED_IMPLEMENTATION: ${env.SELECTED_IMPLEMENTATION}"
                    
                    // Create config map with hardcoded implementation
                    def config = [
                        implementation: 'ec2', // Hardcoded to 'ec2'
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        sshKeyId: env.SSH_KEY_ID,
                        appFile: env.APP_FILE,
                        emailRecipient: env.EMAIL_RECIPIENT,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH
                    ]
                    
                    echo "DEBUG: Config implementation: ${config.implementation}"
                    
                    // Call the base pipeline implementation
                    basePipelineImpl.initialize(config)
                    basePipelineImpl.checkout(config)
                    
                    if (env.EXECUTION_TYPE == 'FULL_DEPLOY' || env.EXECUTION_TYPE == 'MANUAL_APPLY') {
                        echo "DEBUG: Before terraform - Implementation: ${config.implementation}"
                        // Force implementation again before terraform
                        config.implementation = 'ec2'
                        // Fix Terraform variable format
                        echo "DEBUG: Fixing Terraform variable format"
                        terraformInit(config)
                        
                        // Use correct variable format for Terraform plan
                        dir(config.tfWorkingDir) {
                            sh "terraform plan -out=tfplan"
                        }
                        
                        approvals.terraformApplyApproval(config)
                        
                        // Use correct variable format for Terraform apply
                        dir(config.tfWorkingDir) {
                            sh "terraform apply -auto-approve tfplan"
                            archiveArtifacts artifacts: 'terraform.tfstate', fingerprint: true
                        }
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
                    // Create config map with hardcoded implementation
                    def config = [
                        implementation: 'ec2', // Hardcoded to 'ec2'
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
                    // Create config map with hardcoded implementation
                    def config = [
                        implementation: 'ec2', // Hardcoded to 'ec2'
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
