### Updated Root main.tf (Fixed Errors)

provider "aws" {
  region = var.aws_region
}

module "vpc" {
  source              = "./modules/vpc"
  vpc_cidr            = var.vpc_cidr
  public_subnet_cidrs = var.public_subnet_cidrs
  availability_zones  = var.availability_zones
}

module "security_group" {
  source = "./modules/security_group"
  vpc_id = module.vpc.vpc_id
}

/*
module "alb" {
  source            = "./modules/alb"
  vpc_id            = module.vpc.vpc_id
  subnet_ids        = module.vpc.public_subnet_ids # 🔥 Fix: Use correct attribute
  security_group_id = module.security_group.security_group_id
  listener_port = var.listener_port
  health_check_path = var.health_check_path
  health_check_interval = var.health_check_interval
  health_check_timeout = var.health_check_timeout
  healthy_threshold   = var.healthy_threshold
  unhealthy_threshold = var.unhealthy_threshold
}
*/



module "elastic_beanstalk" {
  source            = "./modules/elastic_beanstalk"
  app_name            = var.app_name
  platform_arn = var.platform_arn
  app_zip_path        = var.app_zip_path
  version_label       = var.version_label
  instance_type       = var.instance_type
  wsgi_path = var.wsgi_path
  health_check_path = var.health_check_path
  app_source_dir = var.app_source_dir

  s3_bucket            = var.s3_bucket
  blue_env_name        = var.blue_env_name
  green_env_name       = var.green_env_name
  env_settings         = var.env_settings
  environment_tier     = var.environment_tier
  rolling_update_enabled = var.rolling_update_enabled
  iam_service_role_arn = var.iam_service_role_arn
  cname_prefix_blue = var.cname_prefix_blue
  cname_prefix_green = var.cname_prefix_green
}


/*
module "asg" {
  source               = "./modules/asg"
  subnet_ids           = module.vpc.public_subnet_ids # Ensure this output exists
  security_group_id    = module.security_group.security_group_id
  key_name             = var.key_name
  #alb_target_group_arn = var.alb_target_group_arn
  alb_target_group_arns = [ module.alb.blue_target_group_arn, module.alb.green_target_group_arn ]
  desired_capacity =  var.desired_capacity
  min_size = var.min_size
  max_size = var.max_size
}

### ALB Module variables.tf
*/



