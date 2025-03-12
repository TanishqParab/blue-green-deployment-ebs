#!/bin/bash

# Update package manager
sudo yum update -y

# Install Python and dependencies
sudo yum install -y python3 python3-pip git unzip aws-cli

# Install Flask (for the demo app)
pip3 install flask

# Install Java (Required for Jenkins)
sudo yum install -y java-17-amazon-corretto

# Install Jenkins
sudo wget -O /etc/yum.repos.d/jenkins.repo https://pkg.jenkins.io/redhat-stable/jenkins.repo
sudo rpm --import https://pkg.jenkins.io/redhat-stable/jenkins.io-2023.key
sudo yum upgrade -y
sudo yum install -y jenkins

# Start & Enable Jenkins
sudo systemctl start jenkins
sudo systemctl enable jenkins

# Install Terraform
wget -O terraform.zip https://releases.hashicorp.com/terraform/1.5.7/terraform_1.5.7_linux_amd64.zip
unzip terraform.zip
sudo mv terraform /usr/local/bin/
rm terraform.zip

# Disable Jenkins security (Temporary Fix for permissions)
sudo sed -i 's/<useSecurity>true<\/useSecurity>/<useSecurity>false<\/useSecurity>/' /var/lib/jenkins/config.xml

# Restart Jenkins to apply changes
sudo systemctl restart jenkins

# Wait for Jenkins to fully start
sleep 60  

# Download Jenkins CLI tool
sudo su - jenkins -c "wget http://localhost:8080/jnlpJars/jenkins-cli.jar"

# Retrieve Jenkins initial admin password
ADMIN_PASS=$(sudo cat /var/lib/jenkins/secrets/initialAdminPassword)

# Store Jenkins credentials in a secure file
echo "Jenkins Admin Password: $ADMIN_PASS" | sudo tee /home/ec2-user/jenkins-credentials.txt
echo "Jenkins URL: http://localhost:8080" | sudo tee -a /home/ec2-user/jenkins-credentials.txt
sudo chmod 600 /home/ec2-user/jenkins-credentials.txt  # Secure the credentials file

# Install Jenkins plugins (Terraform, Pipeline, Git, AWS Credentials)
java -jar jenkins-cli.jar -auth admin:$ADMIN_PASS -s http://localhost:8080 install-plugin terraform pipeline git aws-credentials configuration-as-code

# Restart Jenkins after installing plugins
java -jar jenkins-cli.jar -auth admin:$ADMIN_PASS -s http://localhost:8080 restart

# Wait for Jenkins to restart
sleep 30  

# Create a new Jenkins pipeline from the uploaded Jenkinsfile
java -jar jenkins-cli.jar -auth admin:$ADMIN_PASS -s http://localhost:8080 create-job BlueGreenPipeline < /home/ec2-user/Jenkinsfile

# Trigger the initial build of the pipeline
java -jar jenkins-cli.jar -auth admin:$ADMIN_PASS -s http://localhost:8080 build BlueGreenPipeline

echo "Jenkins & Dependencies Installed Successfully!"