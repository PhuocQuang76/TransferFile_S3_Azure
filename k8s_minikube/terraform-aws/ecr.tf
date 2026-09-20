resource "aws_ecr_repository" "file_transfer_repo" {
  name                 = "file-transfer-service"
  image_tag_mutability = "MUTABLE"
  force_delete         = true # <--- This allows Terraform to delete the repo even if it has images inside

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name        = "${var.project_name}-${var.environment}-ecr"
    Environment = var.environment
  }
}
# Keeps only the 2 most recent images. Without this, every push of a mutable tag
# orphans the previous image as untagged - and untagged images still bill.
resource "aws_ecr_lifecycle_policy" "keep_recent" {
  repository = aws_ecr_repository.file_transfer_repo.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Keep only the 2 most recent images"
        selection = {
          tagStatus   = "any"
          countType   = "imageCountMoreThan"
          countNumber = 2
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}
