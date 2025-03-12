resource "aws_instance" "blue" {
  ami           = "ami-05b10e08d247fb927"
  instance_type = var.instance_type
  key_name      = var.key_name
  subnet_id     = var.subnet_id
  security_groups = [var.ec2_security_group_id]

  tags = {
    Name = "Blue-Instance"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/install_dependencies.sh"
    destination = "/home/ec2-user/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/app.py"
    destination = "/home/ec2-user/app.py"
  }

  provisioner "file" {
    source      = "${path.root}/Jenkinsfile"
    destination = "/home/ec2-user/Jenkinsfile"
  }

  provisioner "remote-exec" {
    inline = [
      "chmod +x /home/ec2-user/install_dependencies.sh",
      "sudo /home/ec2-user/install_dependencies.sh"
    ]
  }

  connection {
    type        = "ssh"
    user        = "ec2-user"
    private_key = base64decode(var.private_key_base64)
    host        = self.public_ip
  }
}

resource "aws_instance" "green" {
  ami           = "ami-05b10e08d247fb927"
  instance_type = var.instance_type
  key_name      = var.key_name
  subnet_id     = var.subnet_id
  security_groups = [var.ec2_security_group_id]

  tags = {
    Name = "Green-Instance"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/install_dependencies.sh"
    destination = "/home/ec2-user/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/scripts/app.py"
    destination = "/home/ec2-user/app.py"
  }

  provisioner "file" {
    source      = "${path.root}/Jenkinsfile"
    destination = "/home/ec2-user/Jenkinsfile"
  }

  provisioner "remote-exec" {
    inline = [
      "chmod +x /home/ec2-user/install_dependencies.sh",
      "sudo /home/ec2-user/install_dependencies.sh"
    ]
  }

  connection {
    type        = "ssh"
    user        = "ec2-user"
    private_key = base64decode(var.private_key_base64)
    host        = self.public_ip
  }
}
