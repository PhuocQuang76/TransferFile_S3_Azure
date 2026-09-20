# Values printed after `terraform apply`, and readable any time with `terraform output`.
# Outputs are always top-level blocks - they cannot be nested inside a resource.

output "github_actions_role_arn" {
  description = "IAM role the GitHub Actions workflow assumes via OIDC"
  value       = aws_iam_role.github_actions_role.arn
}

output "ecr_repository_url" {
  description = "Full ECR URI to tag and push images to"
  value       = aws_ecr_repository.file_transfer_repo.repository_url
}

output "s3_bucket_name" {
  description = "Source bucket the transfer service reads from"
  value       = aws_s3_bucket.file_source_bucket.id
}
