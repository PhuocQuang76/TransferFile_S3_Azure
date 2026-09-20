resource "aws_secretsmanager_secret" "app_config" {
  name                    = "s3-to-azure/credentials"
  recovery_window_in_days = 0 # Immediate deletion on destroy for dev/testing

  tags = {
    Name        = "${var.project_name}-${var.environment}-app-config"
    Environment = var.environment
  }
}

resource "aws_secretsmanager_secret_version" "app_config" {
  secret_id = aws_secretsmanager_secret.app_config.id

  secret_string = jsonencode({
    aws_access_key_id          = var.aws_access_key_id
    aws_secret_access_key      = var.aws_secret_access_key
    s3_bucket_name             = aws_s3_bucket.file_source_bucket.id
    s3_region                  = var.aws_region
    azure_storage_account_name = "s3azureconnector123"
    azure_storage_account_key  = var.azure_storage_account_key
    azure_container_name       = "s3-azure-connector"
    mysql_username             = "root"
    mysql_password             = "root"
    mysql_url                  = "jdbc:mysql://placeholder-host:3306/filetransfer?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
  })
}

output "secret_arn" {
  value       = aws_secretsmanager_secret.app_config.arn
  description = "The ARN of the Secrets Manager container."
}