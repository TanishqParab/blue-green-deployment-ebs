resource "aws_lb" "main" {
  name               = "blue-green-alb"
  internal           = false
  load_balancer_type = "application"
  security_groups    = [var.security_group_id]
  subnets            = var.subnet_ids  # Now it will receive a list of subnets
}


resource "aws_lb_target_group" "blue" {
  name     = "blue-tg"
  port     = 5000
  protocol = "HTTP"
  vpc_id   = var.vpc_id
}

resource "aws_lb_target_group" "green" {
  name     = "green-tg"
  port     = 5000
  protocol = "HTTP"
  vpc_id   = var.vpc_id
}
