variable "key_name" {
  description = "The name of the key pair to use for the instance"
  type        = string
}

variable "subnet_ids" {
  description = "The list of subnet IDs to launch resources in"
  type        = list(string)
}

variable "security_group_id" {
  description = "The ID of the security group"
  type        = string
}

variable "alb_target_group_arns" {
  description = "List of ALB Target Group ARNs to attach the ASG instances"
  type        = list(string)
  default     = []
}

variable "min_size" {
  description = "Minimum size of the Auto Scaling Group"
  type        = number
  default     = 1
}

variable "max_size" {
  description = "Maximum size of the Auto Scaling Group"
  type        = number
  default     = 2
}

variable "desired_capacity" {
  description = "Desired capacity of the Auto Scaling Group"
  type        = number
  default     = 1
}

variable "launch_template_name_prefix" {
  description = "Name prefix for the launch template"
  type        = string
  default     = "blue-green-launch-template"
}

variable "image_id" {
  description = "AMI ID to use for the launch template"
  type        = string
  default     = "ami-05b10e08d247fb927"
}

variable "instance_type" {
  description = "Instance type to use for the launch template"
  type        = string
  default     = "t3.micro"
}

variable "associate_public_ip_address" {
  description = "Whether to associate a public IP address with instances"
  type        = bool
  default     = true
}

variable "asg_name" {
  description = "Name for the Auto Scaling Group"
  type        = string
  default     = "blue_green_asg"
}

variable "health_check_type" {
  description = "Type of health check to perform (EC2 or ELB)"
  type        = string
  default     = "ELB"
}

variable "health_check_grace_period" {
  description = "Time (in seconds) after instance comes into service before checking health"
  type        = number
  default     = 300
}

variable "termination_policies" {
  description = "List of policies to decide how the instances in the Auto Scaling Group should be terminated"
  type        = list(string)
  default     = ["OldestInstance"]
}

variable "min_healthy_percentage" {
  description = "Minimum percentage of healthy instances during instance refresh"
  type        = number
  default     = 50
}

variable "instance_warmup" {
  description = "Time (in seconds) for new instances to warm up during instance refresh"
  type        = number
  default     = 60
}

variable "user_data_script" {
  description = "User data script to run on instance launch"
  type        = string
  default     = <<EOF
#!/bin/bash
# Update packages
sudo yum update -y

# Install Git, Python, and Flask
sudo yum install -y git python3
sudo pip3 install flask

# Clone your GitHub repository
mkdir -p /home/ec2-user/app
cd /home/ec2-user/app
git clone https://github.com/TanishqParab/blue-green-deployment.git .

# Set permissions
sudo chown -R ec2-user:ec2-user /home/ec2-user/app

# Create a Flask systemd service
cat <<EOL | sudo tee /etc/systemd/system/flask-app.service
[Unit]
Description=Flask App
After=network.target

[Service]
User=ec2-user
WorkingDirectory=/home/ec2-user/app
ExecStart=/usr/bin/python3 /home/ec2-user/app/blue-green-deployment/modules/ec2/scripts/app.py
Restart=always

[Install]
WantedBy=multi-user.target
EOL

# Reload systemd and enable Flask service
sudo systemctl daemon-reload
sudo systemctl enable flask-app
sudo systemctl start flask-app
EOF
}
