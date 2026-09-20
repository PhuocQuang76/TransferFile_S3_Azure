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