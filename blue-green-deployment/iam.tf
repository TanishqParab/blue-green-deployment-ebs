# SSM IAM Role
resource "aws_iam_role" "ssm_instance_role" {
  name               = "SSMInstanceRole"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ec2.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Environment = "Blue-Green"
  }
}

# Attach SSM Managed Policy
resource "aws_iam_role_policy_attachment" "ssm_managed" {
  role       = aws_iam_role.ssm_instance_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonSSMManagedInstanceCore"
}

# Instance Profile
resource "aws_iam_instance_profile" "ssm_instance_profile" {
  name = "SSMInstanceProfile"
  role = aws_iam_role.ssm_instance_role.name
}