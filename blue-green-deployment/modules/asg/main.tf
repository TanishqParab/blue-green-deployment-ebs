############################################
# Launch Template
############################################

resource "aws_launch_template" "app" {
  name_prefix   = var.launch_template_name_prefix
  image_id      = var.image_id
  instance_type = var.instance_type
  key_name      = var.key_name

  network_interfaces {
    associate_public_ip_address = var.associate_public_ip_address
    security_groups             = [var.security_group_id]
  }

  user_data = base64encode(var.user_data_script)
}

############################################
# Auto Scaling Group
############################################

resource "aws_autoscaling_group" "blue_green_asg" {
  name                = var.asg_name
  desired_capacity    = var.desired_capacity
  max_size            = var.max_size
  min_size            = var.min_size
  vpc_zone_identifier = var.subnet_ids
  target_group_arns   = var.alb_target_group_arns

  launch_template {
    id      = aws_launch_template.app.id
    version = "$Latest"
  }

  # Health check configuration
  health_check_type         = var.health_check_type
  health_check_grace_period = var.health_check_grace_period

  # Instance termination policies
  termination_policies = var.termination_policies

  lifecycle {
    ignore_changes = [target_group_arns, desired_capacity, min_size, max_size]
  }

  instance_refresh {
    strategy = "Rolling"
    preferences {
      min_healthy_percentage = var.min_healthy_percentage
      instance_warmup        = var.instance_warmup
    }
  }
}
