output "blue_instance_public_ip" {
  value = module.ec2.blue_instance_ip
  description = "Public IP of the Blue instance"
}

output "green_instance_public_ip" {
  value = module.ec2.green_instance_ip
  description = "Public IP of the Green instance"
}

