resource "aws_launch_template" "app" {
  name_prefix   = "blue-green-launch-template"
  image_id      = "ami-05b10e08d247fb927"
  instance_type = "t3.micro"
  key_name      = var.key_name
  network_interfaces {
    associate_public_ip_address = true
    security_groups = [var.security_group_id]
  }
}

resource "aws_autoscaling_group" "blue" {
  desired_capacity     = 1
  max_size            = 1
  min_size            = 1
  vpc_zone_identifier = var.subnet_ids
  target_group_arns   = [var.alb_target_group_arn]
  
  launch_template {
    id      = aws_launch_template.app.id
    version = "$Latest"
  }
}

