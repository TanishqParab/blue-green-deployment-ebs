resource "random_id" "suffix" {
  byte_length = 4
}

resource "aws_iam_role" "ssm_instance_role" {
  name               = "SSMInstanceRole-${random_id.suffix.hex}"
  assume_role_policy = data.aws_iam_policy_document.assume_role.json
  tags = {
    Environment = "Blue-Green"
  }
}

data "aws_iam_policy_document" "assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role_policy_attachment" "ssm_managed" {
  role       = aws_iam_role.ssm_instance_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

resource "aws_iam_instance_profile" "ssm_instance_profile" {
  name = "SSMInstanceProfile-${random_id.suffix.hex}"
  role = aws_iam_role.ssm_instance_role.name
}

# EB Service Role
resource "aws_iam_role" "beanstalk_service" {
  name = "${var.app_name}-elasticbeanstalk-service-role-${random_id.suffix.hex}"
  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [{
      Action = "sts:AssumeRole",
      Effect = "Allow",
      Principal = {
        Service = "elasticbeanstalk.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "beanstalk_service" {
  role       = aws_iam_role.beanstalk_service.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSElasticBeanstalkEnhancedHealth"
}

resource "aws_iam_role_policy_attachment" "beanstalk_service_worker" {
  role       = aws_iam_role.beanstalk_service.name
  policy_arn = "arn:aws:iam::aws:policy/AWSElasticBeanstalkWorkerTier"
}

resource "aws_iam_role_policy_attachment" "beanstalk_service_managed_updates" {
  role       = aws_iam_role.beanstalk_service.name
  policy_arn = "arn:aws:iam::aws:policy/AWSElasticBeanstalkManagedUpdatesCustomerRolePolicy"
}

# EB EC2 Role
resource "aws_iam_role" "beanstalk_ec2" {
  name = "${var.app_name}-elasticbeanstalk-ec2-role-${random_id.suffix.hex}"
  assume_role_policy = jsonencode({
    Version = "2012-10-17",
    Statement = [{
      Action = "sts:AssumeRole",
      Effect = "Allow",
      Principal = {
        Service = "ec2.amazonaws.com"
      }
    }]
  })
}

resource "aws_iam_instance_profile" "beanstalk_ec2" {
  name = "${var.app_name}-elasticbeanstalk-ec2-profile-${random_id.suffix.hex}"
  role = aws_iam_role.beanstalk_ec2.name
}

resource "aws_iam_role_policy_attachment" "beanstalk_ec2" {
  role       = aws_iam_role.beanstalk_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AWSElasticBeanstalkWebTier"
}

resource "aws_iam_role_policy_attachment" "beanstalk_ec2_worker" {
  role       = aws_iam_role.beanstalk_ec2.name
  policy_arn = "arn:aws:iam::aws:policy/AWSElasticBeanstalkWorkerTier"
}

resource "aws_elastic_beanstalk_application" "app" {
  name        = var.app_name
  description = "Elastic Beanstalk application for Blue-Green deployment"
}

resource "aws_s3_bucket" "app_bucket" {
  bucket        = "${var.app_name}-app-bucket-${random_id.suffix.hex}"
  force_destroy = true

  lifecycle {
    prevent_destroy = false
  }
}
resource "null_resource" "package_app" {
  triggers = {
    source_hash = filemd5("${var.app_source_dir}/requirements.txt")
  }

  provisioner "local-exec" {
    command = "python3 modules/elastic_beanstalk/scripts/zip_app.py ${var.app_source_dir} ${var.app_zip_path}"
  }
}

resource "aws_s3_object" "app_zip" {
  depends_on   = [null_resource.package_app]
  bucket       = aws_s3_bucket.app_bucket.id
  key          = "app.zip"
  source       = "${path.root}/app.zip"
  content_type = "application/zip"
  etag         = try(filemd5("${path.root}/app.zip"), "")
}

resource "aws_elastic_beanstalk_application_version" "app_version" {
  name        = var.version_label
  application = aws_elastic_beanstalk_application.app.name
  bucket      = aws_s3_bucket.app_bucket.id
  key         = aws_s3_object.app_zip.key

  depends_on = [
    aws_s3_object.app_zip
  ]
}

resource "aws_elastic_beanstalk_environment" "blue" {
  name                = "${var.app_name}-blue"
  application         = aws_elastic_beanstalk_application.app.name
  platform_arn        = var.platform_arn
  version_label       = aws_elastic_beanstalk_application_version.app_version.name
  cname_prefix        = var.cname_prefix_blue

  depends_on = [
    aws_elastic_beanstalk_application_version.app_version
  ]


  setting {
    namespace = "aws:elasticbeanstalk:environment"
    name      = "ServiceRole"
    value     = aws_iam_role.beanstalk_service.arn
  }

  setting {
    namespace = "aws:autoscaling:launchconfiguration"
    name      = "IamInstanceProfile"
    value     = aws_iam_instance_profile.beanstalk_ec2.name
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment:proxy"
    name      = "ProxyServer"
    value     = "nginx"
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment:proxy:staticfiles"
    name      = "/static/"
    value     = "static/"
  }

  setting {
    namespace = "aws:elasticbeanstalk:container:python"
    name      = "WSGIPath"
    value     = var.wsgi_path
  }

  setting {
    namespace = "aws:elasticbeanstalk:container:python"
    name      = "NumProcesses"
    value     = "2"
  }

  setting {
    namespace = "aws:elasticbeanstalk:container:python"
    name      = "NumThreads"
    value     = "15"
  }

  setting {
    namespace = "aws:elasticbeanstalk:application"
    name      = "Application Healthcheck URL"
    value     = var.health_check_path
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment:process:default"
    name      = "HealthCheckPath"
    value     = var.health_check_path
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment"
    name      = "HealthCheckInterval"
    value     = "60"  # Adjust this value as necessary
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment"
    name      = "Health check grace period"
    value     = "600"
  }

  setting {
    namespace = "aws:autoscaling:launchconfiguration"
    name      = "InstanceType"
    value     = var.instance_type
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment"
    name      = "EnvironmentType"
    value     = "LoadBalanced"
  }

  setting {
    namespace = "aws:elasticbeanstalk:healthreporting:system"
    name      = "SystemType"
    value     = "enhanced"
  }

  tags = {
    Name        = "Blue-Environment"
    Environment = "Blue"
  }
}

resource "aws_elastic_beanstalk_environment" "green" {
  name                = "${var.app_name}-green"
  application         = aws_elastic_beanstalk_application.app.name
  platform_arn        = var.platform_arn
  version_label       = aws_elastic_beanstalk_application_version.app_version.name
  cname_prefix        = var.cname_prefix_green

  depends_on = [
    aws_elastic_beanstalk_application_version.app_version
  ]

  setting {
    namespace = "aws:elasticbeanstalk:environment"
    name      = "ServiceRole"
    value     = aws_iam_role.beanstalk_service.arn
  }

  setting {
    namespace = "aws:autoscaling:launchconfiguration"
    name      = "IamInstanceProfile"
    value     = aws_iam_instance_profile.beanstalk_ec2.name
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment:proxy"
    name      = "ProxyServer"
    value     = "nginx"
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment:proxy:staticfiles"
    name      = "/static/"
    value     = "static/"
  }

  setting {
    namespace = "aws:elasticbeanstalk:container:python"
    name      = "WSGIPath"
    value     = var.wsgi_path
  }

  setting {
    namespace = "aws:elasticbeanstalk:container:python"
    name      = "NumProcesses"
    value     = "2"
  }

  setting {
    namespace = "aws:elasticbeanstalk:container:python"
    name      = "NumThreads"
    value     = "15"
  }

  setting {
    namespace = "aws:elasticbeanstalk:application"
    name      = "Application Healthcheck URL"
    value     = var.health_check_path
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment:process:default"
    name      = "HealthCheckPath"
    value     = var.health_check_path
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment"
    name      = "HealthCheckInterval"
    value     = "60"  # Adjust this value as necessary
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment"
    name      = "Health check grace period"
    value     = "600"
  }

  setting {
    namespace = "aws:autoscaling:launchconfiguration"
    name      = "InstanceType"
    value     = var.instance_type
  }

  setting {
    namespace = "aws:elasticbeanstalk:environment"
    name      = "EnvironmentType"
    value     = "LoadBalanced"
  }

  setting {
    namespace = "aws:elasticbeanstalk:healthreporting:system"
    name      = "SystemType"
    value     = "enhanced"
  }

  tags = {
    Name        = "Green-Environment"
    Environment = "Green"
  }
}
