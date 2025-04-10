resource "aws_launch_template" "app" {
  name_prefix   = "blue-green-launch-template"
  image_id      = "ami-05b10e08d247fb927"
  instance_type = "t3.micro"
  key_name      = var.key_name
  network_interfaces {
    associate_public_ip_address = true
    security_groups = [var.security_group_id]
  }

    user_data = base64encode(<<EOF
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
  )
}



resource "aws_autoscaling_group" "blue_green_asg" {
  name = "blue_green_asg"
  desired_capacity     = var.desired_capacity
  max_size            = var.max_size
  min_size            = var.min_size
  vpc_zone_identifier = var.subnet_ids
  #target_group_arns   = [var.alb_target_group_arn]
  
  launch_template {
    id      = aws_launch_template.app.id
    version = "$Latest"
  }

  # Use the variable for Target Groups
  target_group_arns = var.alb_target_group_arns

  # Enable ELB health checks for better instance recovery
  health_check_type         = "ELB"
  health_check_grace_period = 300

  # Ensure instances are replaced properly when terminated
  termination_policies = ["OldestInstance"]

  lifecycle {
    ignore_changes = [
      target_group_arns,   # ✅ correct attribute name
      desired_capacity,
      min_size,
      max_size
    ]
  }
  
  instance_refresh {
    strategy = "Rolling"
    preferences {
      min_healthy_percentage = 50
      instance_warmup        = 60
    }
  }
}
   #Set scaling policies to none to prevent any further scaling actions
