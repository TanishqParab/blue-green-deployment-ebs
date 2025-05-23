############################################
# Blue Instance
############################################

resource "aws_instance" "blue" {
  ami             = var.ami_id
  instance_type   = var.instance_type
  key_name        = var.key_name
  subnet_id       = var.subnet_id
  security_groups = [var.security_group_id]

  tags = merge(
    {
      Name        = var.blue_instance_name
      Environment = var.environment_tag
    },
    var.additional_tags
  )

  provisioner "file" {
    source      = "${path.module}/${var.install_dependencies_script_path}"
    destination = "/home/${var.ssh_user}/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/${var.app_script_path}"
    destination = "/home/${var.ssh_user}/app.py"
  }

  provisioner "file" {
    source      = "${path.root}/${var.jenkins_file_path}"
    destination = "/home/${var.ssh_user}/Jenkinsfile"
  }

  provisioner "remote-exec" {
    inline = [
      "sudo yum install -y dos2unix",
      "dos2unix /home/${var.ssh_user}/install_dependencies.sh",
      "chmod +x /home/${var.ssh_user}/install_dependencies.sh",
      "sudo /bin/bash /home/${var.ssh_user}/install_dependencies.sh"
    ]
  }

  connection {
    type        = "ssh"
    user        = var.ssh_user
    private_key = base64decode(var.private_key_base64)
    host        = self.public_ip
  }
}

############################################
# Green Instance
############################################

resource "aws_instance" "green" {
  ami             = var.ami_id
  instance_type   = var.instance_type
  key_name        = var.key_name
  subnet_id       = var.subnet_id
  security_groups = [var.security_group_id]

  tags = merge(
    {
      Name        = var.green_instance_name
      Environment = var.environment_tag
    },
    var.additional_tags
  )

  provisioner "file" {
    source      = "${path.module}/${var.install_dependencies_script_path}"
    destination = "/home/${var.ssh_user}/install_dependencies.sh"
  }

  provisioner "file" {
    source      = "${path.module}/${var.app_script_path}"
    destination = "/home/${var.ssh_user}/app.py"
  }

  provisioner "file" {
    source      = "${path.root}/${var.jenkins_file_path}"
    destination = "/home/${var.ssh_user}/Jenkinsfile"
  }

  provisioner "remote-exec" {
    inline = [
      "sudo yum install -y dos2unix",
      "dos2unix /home/${var.ssh_user}/install_dependencies.sh",
      "chmod +x /home/${var.ssh_user}/install_dependencies.sh",
      "sudo /bin/bash /home/${var.ssh_user}/install_dependencies.sh"
    ]
  }

  connection {
    type        = "ssh"
    user        = var.ssh_user
    private_key = base64decode(var.private_key_base64)
    host        = self.public_ip
  }
}
