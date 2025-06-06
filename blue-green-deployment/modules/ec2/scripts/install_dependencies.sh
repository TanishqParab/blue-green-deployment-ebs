#!/bin/bash

# Update package manager
sudo yum update -y

# Install Python and dependencies
sudo yum install -y python3 python3-pip git unzip aws-cli

# Install Flask (for the demo app)
pip3 install flask

# Install Java (Required for other tools if needed)
sudo yum install -y java-17-amazon-corretto

# Install Terraform
wget -O terraform.zip https://releases.hashicorp.com/terraform/1.5.7/terraform_1.5.7_linux_amd64.zip
unzip terraform.zip
sudo mv terraform /usr/local/bin/
rm terraform.zip

echo "Installation of dependencies completed successfully!"
