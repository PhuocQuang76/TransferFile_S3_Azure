# iam-oidc.tf

# 1. Register GitHub Actions as an OIDC Identity Provider in AWS
resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"] # GitHub's standard OIDC thumbprint
}

# 2. Create the IAM Role for GitHub Actions with Trust Policy
resource "aws_iam_role" "github_actions_role" {
  name = "GitHubActionsECRDeployRole" # Must match the workflow's role-to-assume value

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Principal = {
          Federated = aws_iam_openid_connect_provider.github.arn
        }
        Action = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = {
            "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          }
          StringLike = {
            # Exact repo + main branch subject used by GitHub Actions for pushes to main
            "token.actions.githubusercontent.com:sub" = "repo:PhuocQuang76/TransferFile_S3_Azure:ref:refs/heads/main"
          }
        }
      }
    ]
  })
}

# 3. Attach ECR Power User permissions to the role
resource "aws_iam_role_policy_attachment" "ecr_power_user" {
  role       = aws_iam_role.github_actions_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser"
}

output "github_actions_role_arn" {
  value = aws_iam_role.github_actions_role.arn
}