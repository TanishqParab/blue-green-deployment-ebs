// vars/terraformDestroy.groovy

def call(config) {
    if (params.MANUAL_BUILD == 'DESTROY') {
        def buildNumber = input(
            message: "Enter the build number that created the infrastructure (e.g., 42)",
            parameters: [string(name: 'BUILD_NUMBER')]
        )

        dir("${config.tfWorkingDir}") {
            copyArtifacts(
                projectName: env.JOB_NAME,
                selector: specific("${buildNumber}"),
                filter: "terraform.tfstate",
                target: "."
            )

            sh "terraform init"

            def destroyCmd = "terraform destroy -auto-approve"
            if (config.varFile) {
                destroyCmd += " -var-file=${config.varFile}"
            }
            sh destroyCmd
        }
    }
}
