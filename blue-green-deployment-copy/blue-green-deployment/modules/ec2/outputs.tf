output "blue_instance_ip" {
  value = aws_instance.blue.public_ip
}

output "green_instance_ip" {
  value = aws_instance.green.public_ip
}
