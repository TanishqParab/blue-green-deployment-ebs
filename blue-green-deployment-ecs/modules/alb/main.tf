############################################
# ALB Resources
############################################

resource "aws_lb" "main" {
  name               = var.alb_name
  internal           = var.internal
  load_balancer_type = var.load_balancer_type
  security_groups    = var.security_groups != null ? var.security_groups : [var.security_group_id]
  subnets            = var.public_subnet_ids

  dynamic "access_logs" {
    for_each = var.enable_access_logs ? [1] : []
    content {
      bucket  = var.access_logs_bucket
      prefix  = var.access_logs_prefix
      enabled = var.access_logs_enabled
    }
  }

  idle_timeout               = var.idle_timeout
  enable_deletion_protection = var.enable_deletion_protection
  drop_invalid_header_fields = var.drop_invalid_header_fields

  tags = merge(
    {
      Name        = var.alb_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )

  lifecycle {
    create_before_destroy = true
  }
}

############################################
# Target Groups
############################################

resource "aws_lb_target_group" "blue" {
  name                 = var.blue_target_group_name
  port                 = var.target_group_port
  protocol             = var.target_group_protocol
  vpc_id               = var.vpc_id
  target_type          = var.target_type
  deregistration_delay = var.deregistration_delay

  dynamic "stickiness" {
    for_each = var.target_group_stickiness_enabled ? [1] : []
    content {
      type            = var.target_group_stickiness_type
      cookie_duration = var.target_group_stickiness_duration
      enabled         = var.target_group_stickiness_enabled
    }
  }

  health_check {
    path                = var.health_check_path
    interval            = var.health_check_interval
    timeout             = var.health_check_timeout
    healthy_threshold   = var.healthy_threshold
    unhealthy_threshold = var.unhealthy_threshold
    matcher             = var.health_check_matcher
    port                = var.health_check_port
    protocol            = var.health_check_protocol
  }

  tags = merge(
    {
      Name            = var.blue_target_group_name
      Environment     = var.environment
      Module          = var.module_name
      Terraform       = var.terraform_managed
      DeploymentGroup = var.blue_deployment_group
    },
    var.additional_tags
  )

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_lb_target_group" "green" {
  name                 = var.green_target_group_name
  port                 = var.target_group_port
  protocol             = var.target_group_protocol
  vpc_id               = var.vpc_id
  target_type          = var.target_type
  deregistration_delay = var.deregistration_delay

  dynamic "stickiness" {
    for_each = var.target_group_stickiness_enabled ? [1] : []
    content {
      type            = var.target_group_stickiness_type
      cookie_duration = var.target_group_stickiness_duration
      enabled         = var.target_group_stickiness_enabled
    }
  }

  health_check {
    path                = var.health_check_path
    interval            = var.health_check_interval
    timeout             = var.health_check_timeout
    healthy_threshold   = var.healthy_threshold
    unhealthy_threshold = var.unhealthy_threshold
    matcher             = var.health_check_matcher
    port                = var.health_check_port
    protocol            = var.health_check_protocol
  }

  tags = merge(
    {
      Name            = var.green_target_group_name
      Environment     = var.environment
      Module          = var.module_name
      Terraform       = var.terraform_managed
      DeploymentGroup = var.green_deployment_group
    },
    var.additional_tags
  )

  lifecycle {
    create_before_destroy = true
  }
}

############################################
# Listeners
############################################

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.main.arn
  port              = var.listener_port
  protocol          = var.listener_protocol
  ssl_policy        = var.listener_protocol == "HTTPS" ? var.ssl_policy : null
  certificate_arn   = var.listener_protocol == "HTTPS" ? var.certificate_arn : null

  default_action {
    type = var.forward_action_type
    forward {
      target_group {
        arn    = aws_lb_target_group.blue.arn
        weight = var.blue_weight
      }
      target_group {
        arn    = aws_lb_target_group.green.arn
        weight = var.green_weight
      }
      stickiness {
        enabled  = var.stickiness_enabled
        duration = var.stickiness_duration
      }
    }
  }

  lifecycle {
    create_before_destroy = true
  }
}

# Optional HTTPS listener if enabled
resource "aws_lb_listener" "https" {
  count = var.create_https_listener ? 1 : 0

  load_balancer_arn = aws_lb.main.arn
  port              = var.https_port
  protocol          = var.https_protocol
  ssl_policy        = var.ssl_policy
  certificate_arn   = var.certificate_arn

  default_action {
    type = var.forward_action_type
    forward {
      target_group {
        arn    = aws_lb_target_group.blue.arn
        weight = var.blue_weight
      }
      target_group {
        arn    = aws_lb_target_group.green.arn
        weight = var.green_weight
      }
      stickiness {
        enabled  = var.stickiness_enabled
        duration = var.stickiness_duration
      }
    }
  }
}
