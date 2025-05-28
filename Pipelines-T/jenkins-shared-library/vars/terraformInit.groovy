// vars/terraformInit.groovy

def call(config) {
    echo "Initializing Terraform..."
    dir("${config.tfWorkingDir}") {
        sh "terraform init"
    }
}
