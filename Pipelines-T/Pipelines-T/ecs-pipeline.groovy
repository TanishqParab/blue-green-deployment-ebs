// ecs-pipeline.groovy - Unified ECS pipeline for apply, switch, and rollback operations

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

        // Add parameter for initial deployment
        choice(
            name: 'INITIAL_DEPLOYMENT',
            choices: ['NO', 'YES'],
            description: 'YES: Deploy initial application to Blue service after infrastructure is created'
        )

        choice(
            name: 'CONFIRM_ROLLBACK',
            choices: ['NO', 'YES'],
            description: 'Confirm you want to rollback to previous version?'
        )
        
        // Add parameter for app selection
        choice(
            name: 'APP_NAME',
            choices: ['app_1', 'app_2', 'app_3', 'all'],
            description: 'Select which application to deploy/rollback (app_1 is the default app, "all" deploys all apps)'
        )
    }
    
    triggers {
        githubPush()
    }
    
    environment {
        // Common environment variables
        IMPLEMENTATION = 'ecs'
        AWS_REGION = 'us-east-1'
        AWS_CREDENTIALS_ID = 'aws-credentials'
        ECR_REPO_NAME = 'blue-green-app'
        CONTAINER_NAME = 'blue-green-container'
        CONTAINER_PORT = '80'
        DOCKERFILE = 'Dockerfile'
        APP_FILE = 'app.py'
        EMAIL_RECIPIENT = 'tanishqparab2001@gmail.com'
        REPO_URL = 'https://github.com/TanishqParab/blue-green-deployment-ecs-test'
        REPO_BRANCH = 'main'
        TF_WORKING_DIR = '/var/lib/jenkins/workspace/ECS-Unified-Pipeline/blue-green-deployment'
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
                        echo "Executing ECS ${operation} pipeline..."
                    }
                    
                    // Store the operation for later stages
                    env.SELECTED_OPERATION = operation
                    
                    // Force implementation to be 'ecs'
                    env.IMPLEMENTATION = 'ecs'
                    env.SELECTED_IMPLEMENTATION = 'ecs'
                    echo "DEBUG: Forced implementation to ECS"
                }
            }
        }
        
        stage('Execute Apply') {
            when {
                expression { env.SELECTED_OPERATION == 'APPLY' }
            }
            steps {
                script {
                    // Force implementation to be 'ecs' again
                    env.IMPLEMENTATION = 'ecs'
                    env.SELECTED_IMPLEMENTATION = 'ecs'
                    echo "DEBUG: Environment variables - IMPLEMENTATION: ${env.IMPLEMENTATION}, SELECTED_IMPLEMENTATION: ${env.SELECTED_IMPLEMENTATION}"
                    
                    // Create config map with hardcoded implementation
                    def config = [
                        implementation: 'ecs', // Hardcoded to 'ecs'
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        ecrRepoName: env.ECR_REPO_NAME,
                        containerName: env.CONTAINER_NAME,
                        containerPort: env.CONTAINER_PORT,
                        dockerfile: env.DOCKERFILE,
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
                        config.implementation = 'ecs'
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
                    
                    if (params.MANUAL_BUILD == 'DESTROY') {
                        approvals.terraformDestroyApproval(config)
                        ecsUtils.cleanResources(config)
                        terraformDestroy(config)
                    }
                }
            }
        }


        // Add new stage for initial deployment
        stage('Execute Initial Deployment') {
            when {
                allOf {
                    expression { env.SELECTED_OPERATION == 'APPLY' }
                    expression { params.INITIAL_DEPLOYMENT == 'YES' }
                    expression { params.MANUAL_BUILD != 'DESTROY' }
                }
            }
            steps {
                script {
                    // Create config map with hardcoded implementation
                    def config = [
                        implementation: 'ecs',
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        ecrRepoName: env.ECR_REPO_NAME,
                        containerName: env.CONTAINER_NAME,
                        containerPort: env.CONTAINER_PORT,
                        dockerfile: env.DOCKERFILE,
                        appFile: env.APP_FILE,
                        emailRecipient: env.EMAIL_RECIPIENT,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH
                    ]
                    
                    echo "🚀 Executing initial deployment for ECS..."
                    
                    // Handle multi-app deployment
                    if (params.APP_NAME == 'all' || params.APP_NAME == null) {
                        // Deploy all apps
                        ['app_1', 'app_2', 'app_3'].each { appName ->
                            echo "Deploying initial application: ${appName}"
                            def appConfig = config.clone()
                            appConfig.appName = appName
                            ecsInitialDeploymentImpl.deployInitialApplication(appConfig)
                        }
                    } else {
                        // Deploy single app
                        config.appName = params.APP_NAME
                        ecsInitialDeploymentImpl.deployInitialApplication(config)
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
                        implementation: 'ecs', 
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        ecrRepoName: env.ECR_REPO_NAME,
                        containerName: env.CONTAINER_NAME,
                        containerPort: env.CONTAINER_PORT,
                        dockerfile: env.DOCKERFILE,
                        appFile: env.APP_FILE,
                        emailRecipient: env.EMAIL_RECIPIENT,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH
                    ]
                    
                    // Handle multi-app deployment
                    if (params.APP_NAME == 'all' || params.APP_NAME == null) {
                        // Deploy all apps
                        ['app_1', 'app_2', 'app_3'].each { appName ->
                            echo "Switching application: ${appName}"
                            def appConfig = config.clone()
                            appConfig.appName = appName
                            
                            // Call the switch pipeline implementation for this app
                            switchPipelineImpl.initialize(appConfig)
                            switchPipelineImpl.checkout(appConfig)
                            switchPipelineImpl.detectChanges(appConfig)
                            
                            if (env.DEPLOY_NEW_VERSION == 'true') {
                                switchPipelineImpl.fetchResources(appConfig)
                                switchPipelineImpl.ensureTargetGroupAssociation(appConfig)
                                switchPipelineImpl.updateApplication(appConfig)
                                switchPipelineImpl.testEnvironment(appConfig)
                                switchPipelineImpl.manualApprovalBeforeSwitchTrafficECS(appConfig)
                                switchPipelineImpl.switchTraffic(appConfig)
                                switchPipelineImpl.postSwitchActions(appConfig)
                            }
                        }
                    } else {
                        // Deploy single app
                        config.appName = params.APP_NAME
                        
                        // Call the switch pipeline implementation
                        switchPipelineImpl.initialize(config)
                        switchPipelineImpl.checkout(config)
                        switchPipelineImpl.detectChanges(config)
                        
                        if (env.DEPLOY_NEW_VERSION == 'true') {
                            switchPipelineImpl.fetchResources(config)
                            switchPipelineImpl.ensureTargetGroupAssociation(config)
                            switchPipelineImpl.updateApplication(config)
                            switchPipelineImpl.testEnvironment(config)
                            switchPipelineImpl.manualApprovalBeforeSwitchTrafficECS(config)
                            switchPipelineImpl.switchTraffic(config)
                            switchPipelineImpl.postSwitchActions(config)
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
                    // Create config map with hardcoded implementation
                    def config = [
                        implementation: 'ecs', 
                        awsRegion: env.AWS_REGION,
                        awsCredentialsId: env.AWS_CREDENTIALS_ID,
                        tfWorkingDir: env.TF_WORKING_DIR,
                        ecrRepoName: env.ECR_REPO_NAME,
                        containerName: env.CONTAINER_NAME,
                        containerPort: env.CONTAINER_PORT,
                        dockerfile: env.DOCKERFILE,
                        appFile: env.APP_FILE,
                        emailRecipient: env.EMAIL_RECIPIENT,
                        repoUrl: env.REPO_URL,
                        repoBranch: env.REPO_BRANCH
                    ]
                    
                    // Handle multi-app rollback
                    if (params.APP_NAME == 'all' || params.APP_NAME == null) {
                        // Rollback all apps
                        ['app_1', 'app_2', 'app_3'].each { appName ->
                            echo "Rolling back application: ${appName}"
                            def appConfig = config.clone()
                            appConfig.appName = appName
                            
                            // Call the rollback pipeline implementation for this app
                            rollbackPipelineImpl.initialize(appConfig)
                            rollbackPipelineImpl.fetchResources(appConfig)
                            rollbackPipelineImpl.prepareRollback(appConfig)
                            rollbackPipelineImpl.testRollbackEnvironment(appConfig)
                            rollbackPipelineImpl.manualApprovalBeforeRollbackECS(appConfig)
                            rollbackPipelineImpl.executeRollback(appConfig)
                            rollbackPipelineImpl.postRollbackActions(appConfig)
                        }
                    } else {
                        // Rollback single app
                        config.appName = params.APP_NAME
                        
                        // Call the rollback pipeline implementation
                        rollbackPipelineImpl.initialize(config)
                        rollbackPipelineImpl.fetchResources(config)
                        rollbackPipelineImpl.prepareRollback(config)
                        rollbackPipelineImpl.testRollbackEnvironment(config)
                        rollbackPipelineImpl.manualApprovalBeforeRollbackECS(config)
                        rollbackPipelineImpl.executeRollback(config)
                        rollbackPipelineImpl.postRollbackActions(config)
                    }
                }
            }
        }
    }
}