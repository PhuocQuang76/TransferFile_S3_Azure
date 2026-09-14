variable "aws_region" {
  type    = string
  default = "us-east-1"
}

variable "project_name" {
  type    = string
  default = "file-transfer"
}

variable "environment" {
  type    = string
  default = "prod"
}

variable "db_name" {
  type    = string
  default = "filetransfer"
}

variable "db_username" {
  type    = string
  default = "root"
}

variable "db_password" {
  type        = string
  description = "Database master password"
  sensitive   = true
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "db_allocated_storage" {
  type    = number
  default = 20
}

variable "azure_storage_account_key" {
  type        = string
  description = "Azure Storage Account Key for container connection"
  sensitive   = true
  default     = "placeholder_azure_key"
}

variable "github_repository" {
  type        = string
  description = "The GitHub repository name"
  default     = "PhuocQuang76/TransferFile_S3_Azure"
}

variable "aws_access_key_id" {
  type        = string
  description = "AWS Access Key ID for the application"
  sensitive   = true
}

variable "aws_secret_access_key" {
  type        = string
  description = "AWS Secret Access Key for the application"
  sensitive   = true
}