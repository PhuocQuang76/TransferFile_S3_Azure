# iam-oidc.tf

resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  thumbprint_list = [
    "6938fd4d98bab03faadb97b34396831e3780aea1",
    "a031c47182908b932187ecf53d537f191b7d8778"
  ]
}

resource "aws_iam_role" "github_actions_role" {
  name = "ECRDeployRole" # <--- Clean name avoiding restricted keywords

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
            # GitHub now sends ID-qualified subjects: repo:<owner>@<ownerId>/<repo>@<repoId>:ref:...
            # Matching on the immutable numeric IDs also survives a rename of the user or repo.
            "token.actions.githubusercontent.com:sub" = "repo:PhuocQuang76@37393971/TransferFile_S3_Azure@1356090642:*"
          }
        }
      }
    ]
  })
}

resource "aws_iam_role_policy_attachment" "ecr_power_user" {
  role       = aws_iam_role.github_actions_role.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser"
}
