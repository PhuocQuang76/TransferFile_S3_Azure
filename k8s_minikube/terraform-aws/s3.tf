resource "aws_s3_bucket" "file_source_bucket" {
  bucket = "aileen-bucket-011243863866"

  tags = {
    Name        = "${var.project_name}-${var.environment}-bucket"
    Environment = var.environment
  }

}

resource "aws_s3_bucket_public_access_block" "block" {
  bucket = aws_s3_bucket.file_source_bucket.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}