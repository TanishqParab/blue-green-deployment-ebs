############################################
# ECS Cluster
############################################

resource "aws_ecs_cluster" "blue_green_cluster" {
  name = var.ecs_cluster_name

  setting {
    name  = "containerInsights"
    value = var.enable_container_insights ? "enabled" : "disabled"
  }

  tags = merge(
    {
      Name        = var.ecs_cluster_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )

  dynamic "configuration" {
    for_each = var.enable_fargate_capacity_providers ? [1] : []
    content {
      execute_command_configuration {
        logging = var.execute_command_logging
      }
    }
  }
}

############################################
# CloudWatch Logs
############################################

# Create CloudWatch Log Group if container logs are enabled
resource "aws_cloudwatch_log_group" "ecs_logs" {
  count = var.enable_container_logs ? 1 : 0

  name              = var.log_group_name
  retention_in_days = var.log_retention_days

  tags = merge(
    {
      Name        = "${var.ecs_cluster_name}-logs"
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

############################################
# IAM Roles
############################################

resource "aws_iam_role" "ecs_task_execution_role" {
  name = var.execution_role_name

  assume_role_policy = jsonencode({
    Version = var.iam_policy_version
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = var.iam_service_principal
        }
      }
    ]
  })

  tags = merge(
    {
      Name        = var.execution_role_name
      Environment = var.environment
      Module      = var.module_name
      Terraform   = var.terraform_managed
    },
    var.additional_tags
  )
}

resource "aws_iam_role_policy_attachment" "ecs_task_execution_policy" {
  role       = aws_iam_role.ecs_task_execution_role.name
  policy_arn = var.task_execution_policy_arn
}

# Add additional policy for CloudWatch Logs if enabled
resource "aws_iam_role_policy" "ecs_logs_policy" {
  count = var.enable_container_logs ? 1 : 0

  name = var.logs_policy_name
  role = aws_iam_role.ecs_task_execution_role.id

  policy = jsonencode({
    Version = var.iam_policy_version
    Statement = [
      {
        Action = [
          "logs:CreateLogStream",
          "logs:PutLogEvents"
        ]
        Effect   = "Allow"
        Resource = "${aws_cloudwatch_log_group.ecs_logs[0].arn}${var.log_resource_suffix}"
      }
    ]
  })
}

############################################
# Blue Deployment Resources
############################################

resource "aws_ecs_task_definition" "blue_task" {
  family                   = var.task_family
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn
  task_role_arn            = var.task_role_arn != null ? var.task_role_arn : aws_iam_role.ecs_task_execution_role.arn
  network_mode             = var.network_mode
  requires_compatibilities = var.requires_compatibilities
  cpu                      = var.cpu
  memory                   = var.memory

  container_definitions = jsonencode([{
    name      = var.container_name
    image     = var.container_image
    essential = var.container_essential
    cpu       = tonumber(var.cpu)
    memory    = tonumber(var.memory)
    portMappings = [{
      containerPort = var.container_port
      hostPort      = var.container_port
      protocol      = var.container_protocol
    }]
    logConfiguration = var.enable_container_logs ? {
      logDriver = var.log_driver
      options = {
        "awslogs-group"         = var.log_group_name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = var.blue_log_stream_prefix
      }
    } : null
    environment = var.container_environment
    secrets     = var.container_secrets
  }])

  dynamic "volume" {
    for_each = var.task_volumes
    content {
      name = volume.value.name

      dynamic "efs_volume_configuration" {
        for_each = volume.value.efs_volume_configuration != null ? [volume.value.efs_volume_configuration] : []
        content {
          file_system_id          = efs_volume_configuration.value.file_system_id
          root_directory          = lookup(efs_volume_configuration.value, "root_directory", var.efs_root_directory_default)
          transit_encryption      = lookup(efs_volume_configuration.value, "transit_encryption", var.efs_transit_encryption_default)
          transit_encryption_port = lookup(efs_volume_configuration.value, "transit_encryption_port", null)
        }
      }
    }
  }

  tags = merge(
    {
      Name           = "${var.task_family}${var.blue_task_name_suffix}"
      Environment    = var.environment
      Module         = var.module_name
      Terraform      = var.terraform_managed
      DeploymentType = var.blue_deployment_type
    },
    var.additional_tags
  )

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_ecs_service" "blue_service" {
  name            = var.blue_service_name
  cluster         = aws_ecs_cluster.blue_green_cluster.id
  task_definition = aws_ecs_task_definition.blue_task.arn
  desired_count   = var.desired_count
  launch_type     = var.launch_type

  deployment_maximum_percent         = var.deployment_maximum_percent
  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  health_check_grace_period_seconds  = var.health_check_grace_period_seconds

  dynamic "capacity_provider_strategy" {
    for_each = var.enable_fargate_capacity_providers ? var.capacity_provider_strategy : []
    content {
      capacity_provider = capacity_provider_strategy.value.capacity_provider
      weight            = capacity_provider_strategy.value.weight
      base              = lookup(capacity_provider_strategy.value, "base", null)
    }
  }

  network_configuration {
    subnets          = var.public_subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = var.assign_public_ip
  }

  load_balancer {
    target_group_arn = var.blue_target_group_arn
    container_name   = var.container_name
    container_port   = var.container_port
  }

  tags = merge(
    {
      Name           = var.blue_service_tag_name
      Environment    = var.environment
      Module         = var.module_name
      Terraform      = var.terraform_managed
      DeploymentType = var.blue_deployment_type
    },
    var.additional_tags
  )

  lifecycle {
    ignore_changes = [desired_count]
  }
}

############################################
# Green Deployment Resources
############################################

resource "aws_ecs_task_definition" "green_task" {
  family                   = "${var.task_family}${var.green_task_family_suffix}"
  execution_role_arn       = aws_iam_role.ecs_task_execution_role.arn
  task_role_arn            = var.task_role_arn != null ? var.task_role_arn : aws_iam_role.ecs_task_execution_role.arn
  network_mode             = var.network_mode
  requires_compatibilities = var.requires_compatibilities
  cpu                      = var.cpu
  memory                   = var.memory

  container_definitions = jsonencode([{
    name      = "${var.container_name}${var.green_container_name_suffix}"
    image     = var.container_image
    essential = var.container_essential
    cpu       = tonumber(var.cpu)
    memory    = tonumber(var.memory)
    portMappings = [{
      containerPort = var.container_port
      hostPort      = var.container_port
      protocol      = var.container_protocol
    }]
    logConfiguration = var.enable_container_logs ? {
      logDriver = var.log_driver
      options = {
        "awslogs-group"         = var.log_group_name
        "awslogs-region"        = var.aws_region
        "awslogs-stream-prefix" = var.green_log_stream_prefix
      }
    } : null
    environment = var.container_environment
    secrets     = var.container_secrets
  }])

  dynamic "volume" {
    for_each = var.task_volumes
    content {
      name = volume.value.name

      dynamic "efs_volume_configuration" {
        for_each = volume.value.efs_volume_configuration != null ? [volume.value.efs_volume_configuration] : []
        content {
          file_system_id          = efs_volume_configuration.value.file_system_id
          root_directory          = lookup(efs_volume_configuration.value, "root_directory", var.efs_root_directory_default)
          transit_encryption      = lookup(efs_volume_configuration.value, "transit_encryption", var.efs_transit_encryption_default)
          transit_encryption_port = lookup(efs_volume_configuration.value, "transit_encryption_port", null)
        }
      }
    }
  }

  tags = merge(
    {
      Name           = "${var.task_family}${var.green_task_name_suffix}"
      Environment    = var.environment
      Module         = var.module_name
      Terraform      = var.terraform_managed
      DeploymentType = var.green_deployment_type
    },
    var.additional_tags
  )

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_ecs_service" "green_service" {
  name            = var.green_service_name
  cluster         = aws_ecs_cluster.blue_green_cluster.id
  task_definition = aws_ecs_task_definition.green_task.arn
  desired_count   = var.green_desired_count
  launch_type     = var.launch_type

  deployment_maximum_percent         = var.deployment_maximum_percent
  deployment_minimum_healthy_percent = var.deployment_minimum_healthy_percent
  health_check_grace_period_seconds  = var.health_check_grace_period_seconds

  dynamic "capacity_provider_strategy" {
    for_each = var.enable_fargate_capacity_providers ? var.capacity_provider_strategy : []
    content {
      capacity_provider = capacity_provider_strategy.value.capacity_provider
      weight            = capacity_provider_strategy.value.weight
      base              = lookup(capacity_provider_strategy.value, "base", null)
    }
  }

  network_configuration {
    subnets          = var.public_subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = var.assign_public_ip
  }

  load_balancer {
    target_group_arn = var.green_target_group_arn
    container_name   = "${var.container_name}${var.green_container_name_suffix}"
    container_port   = var.container_port
  }

  tags = merge(
    {
      Name           = var.green_service_tag_name
      Environment    = var.environment
      Module         = var.module_name
      Terraform      = var.terraform_managed
      DeploymentType = var.green_deployment_type
    },
    var.additional_tags
  )

  lifecycle {
    ignore_changes = [desired_count]
  }
}
