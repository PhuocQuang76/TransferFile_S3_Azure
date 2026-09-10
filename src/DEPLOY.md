✅ Full A-to-Z deployment plan for EC2 + GitHub Actions + RDS
I checked your app and there is one critical issue before deployment:

Your MySQL datasource is hardcoded to localhost in DataSourceConfig.java
The app already reads AWS Secrets Manager in SecretsManagerConfig.java
The server port is 8586 in application.properties
So for EC2 deployment, you must:

create RDS MySQL,
store all DB + AWS + Azure values in Secrets Manager,
change the datasource URL from localhost to the RDS endpoint,
deploy the Docker app on EC2,
make sure S3 and Azure access is valid.


1) Architecture you should use
This is the reliable setup:

                GitHub push

                GitHub Actions

Run Maven tests             Build Docker image

                            Push image to ECR

                            SSH deploy to EC2

                            Run Spring Boot container on EC2

                            RDS MySQL

                            S3 bucket

                            Azure Blob container

---------------------------
2) Create the AWS resources
2.1 Create RDS MySQL
In AWS Console:

RDS → Create database
Engine: MySQL
Template: Free tier or dev
DB instance class: db.t3.micro
Storage: 20 GB
DB name: filetransfer
Master username: admin
Master password: choose a strong password
Public access: No if using private VPC, or Yes for easy testing
Security group: allow 3306 from EC2 security group
After creation, note:

endpoint  
username
password
database name
Example endpoint:
database-1.cxyz123.us-east-1.rds.amazonaws.com

-> In sandbox, you can use public access
Instance Size	Sandbox (db.t4g.micro / db.t3.micro, 20 GiB)
DB instance identifier: database-1
Master username: admin
Self-managed password: admin123

endpoint  : database-1.c2j6u2wgolm9.us-east-1.rds.amazonaws.com
username  : admin
password  : admin123
database name : filetransfer

---------------------------
2.2 Create S3 bucket
aws s3 mb s3://aileen-bucket --region us-east-1

Upload sample file:
echo "test file" > test1.txt
aws s3 cp test1.txt s3://aileen-bucket/test1.txt

---------------------------
2.3 Create Azure Blob container
You need:

Azure storage account
storage account key
container name

Example:
azure:
  storage:
    account-name: s3azureconnector123
    container-name: s3-azure-connector
    connection-string: <YOUR_PRIMARY_CONNECTION_STRING>


---------------------------
3) Create the AWS secret for app config
Your app expects a secret named:
s3-to-azure/credentials

aws secretsmanager create-secret \
  --name "s3-to-azure/credentials" \
  --description "App config for S3 to Azure transfer" \
  --secret-string '{
    "aws_access_key_id": "",
    "aws_secret_access_key": "",
    "s3_bucket_name": "aileen-bucket",
    "s3_region": "us-east-1",
    "azure_storage_account_name": "s3azureconnector123",
    "azure_storage_account_key": "YOUR_AZURE_STORAGE_KEY",
    "azure_container_name": "s3-azure-connector",
    "mysql_username": "admin",
    "mysql_password": "admin",
    "mysql_url": "jdbc:mysql://database-1.cxyz123.us-east-1.rds.amazonaws.com:3306/filetransfer?createDatabaseIfNotExist=true&useSSL=true&serverTimezone=UTC&allowPublicKeyRetrieval=true"
  }' \
  --region us-east-1



Actions
Encryption key  : aws/secretsmanager
Secret name     : s3-to-azure/credentials
Secret ARN      : arn:aws:secretsmanager:us-east-1:843089371063:secret:s3-to-azure/credentials-QPTeyk

---------------------------

4) Fix the app to work on EC2
This is the most important requirement.

Right now your datasource is fixed to localhost in DataSourceConfig.java:

.url("jdbc:mysql://localhost:3306/filetransfer?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true")

On EC2, localhost means “this machine”, not the RDS database.

So change it to:
String url = secrets.getOrDefault(
    "mysql_url",
    "jdbc:mysql://localhost:3306/filetransfer?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true"
);

return DataSourceBuilder.create()
    .type(HikariDataSource.class)
    .url(url)
    .username(username)
    .password(password)
    .driverClassName("com.mysql.cj.jdbc.Driver")
    .build();

This makes the app use the RDS endpoint automatically.

Without this change, the deployed app will start but fail when it tries to connect to MySQL.
---------------------------

5) Create the EC2 instance
EC2 setup:
AMI: Amazon Linux 2023
Type: t3.small or t3.medium
Key pair: create .pem

Security group:
SSH: 22 from your IP
Custom TCP: 8586 from 0.0.0.0/0
MySQL 3306 from EC2 or from your VPC if using RDS


Connect:
pem key : /Users/aileen/Downloads/filetransfer.pem
public IP: 34.204.180.7

chmod 400 /Users/aileen/Downloads/filetransfer.pem
ssh -i /Users/aileen/Downloads/filetransfer.pem ec2-user@34.204.180.7

Install Docker:
sudo dnf update -y
sudo dnf install -y docker
sudo systemctl enable --now docker
sudo usermod -aG docker ec2-user
exit

Reconnect and verify:
docker --version

6) Create ECR repo
aws ecr create-repository --repository-name file-transfer-service --region us-east-1

Save the repository URI.
843089371063.dkr.ecr.us-east-1.amazonaws.com/file-transfer-service


---------------------------
7) Give GitHub Actions permission to ECR

Create an OIDC provider in IAM:

go to IAM

Step1: add provider:
Provider type: Select OpenID Connect.
Provider URL: https://token.actions.githubusercontent.com
Audience: sts.amazonaws.com

Step 2: Create the GitHubActionsECRRole IAM Role
select : custom trust policy

Then create role GitHubActionsECRRole and attach:
AmazonEC2ContainerRegistryPowerUser
Trust policy:
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::<ACCOUNT_ID>:oidc-provider/token.actions.githubusercontent.com"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "token.actions.githubusercontent.com:aud": "sts.amazonaws.com"
        },
        "StringLike": {
          "token.actions.githubusercontent.com:sub": "repo:<YOUR_GITHUB_USERNAME>/<YOUR_REPO_NAME>:*"
        }
      }
    }
  ]
}

and attach:
In the left menu, click Roles.Search for and click GitHubActionsECRRole (or create it if you haven't already).Click the Permissions tab.Click Add permissions $\rightarrow$ select Attach policies.In the search box, type AmazonEC2ContainerRegistryPowerUser.Check the box next to AmazonEC2ContainerRegistryPowerUser.Scroll to the bottom and click Add permission

Then store the role ARN in GitHub secret:

AWS_ROLE_ARN :arn:aws:iam::843089371063:role/GitHubActionsECRRole

---------------------------

8) Add Dockerfile
Create Dockerfile:

FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN chmod +x mvnw
RUN ./mvnw clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8586
ENTRYPOINT ["java", "-jar", "app.jar"]

This matches your app port in application.properties.

---------------------------
9) Add GitHub Actions pipeline
Create .github/workflows/deploy.yml:
name: CI/CD to EC2

on:
  push:
    branches: [ "main" ]

permissions:
  id-token: write
  contents: read

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up Java 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
          cache: 'maven'

      - name: Run tests
        run: |
          chmod +x mvnw
          ./mvnw clean test

      - name: Configure AWS credentials
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: us-east-1

      - name: Login to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Build and push Docker image
        env:
          ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          ECR_REPOSITORY: file-transfer-service
          IMAGE_TAG: ${{ github.sha }}
        run: |
          docker build -t $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG \
                       -t $ECR_REGISTRY/$ECR_REPOSITORY:latest .
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:latest

      - name: Deploy to EC2
        uses: appleboy/ssh-action@v1.0.0
        env:
          ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          ECR_REPOSITORY: file-transfer-service
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USERNAME }}
          key: ${{ secrets.EC2_SSH_KEY }}
          envs: ECR_REGISTRY,ECR_REPOSITORY
          script: |
            aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin $ECR_REGISTRY

            docker stop file-transfer-app || true
            docker rm file-transfer-app || true

            docker pull $ECR_REGISTRY/$ECR_REPOSITORY:latest

            docker run -d \
              --name file-transfer-app \
              --restart always \
              -p 8586:8586 \
              $ECR_REGISTRY/$ECR_REPOSITORY:latest


GitHub secrets needed:

AWS_ROLE_ARN : arn:aws:iam::843089371063:role/GitHubActionsECRRole
EC2_HOST   : 34.204.180.7
EC2_USERNAME  :  ec2-user
EC2_SSH_KEY   :
-----BEGIN RSA PRIVATE KEY-----
MIIEogIBAAKCAQEApXquw0UTywfDP5JOA98Le+xIpQReI87yceJpFDlrcbzVOcNe
Q94WDCAJE4M16bopiz9YvFmEf0T2aS3qYFLFhpTaAsWVY545Rla9BssLJTR4jYFD
6pzZ6c+XcEAwckRo8lLzkUXLUv5CsWB1jsdyBAlXx0+UaTcBXFqS4RxF4aDvRNW1
JgzHM2rPgajrbOgGgi7jGQhy/K6nz4/6sWxWF9tTLuXLCzR6sX2WAQHWE9xNojJ3
ZFAMmjsytkF6Zm+3StnRBQmqdK1T3FTi2D4pgVU4bRgIN8b/SeVk3nGXLXD7u6L7
u4z0G+Pr15jxd9A2ZFwram5aNBstcTIpvMUtNQIDAQABAoIBACXo8zydCKnMv3hC
5kiQwqrDOOlFFOQTNwvMGNKfTPQjlJ3FGkVmDZr9g9bKioFfOsFB4+xSfb5CaEk0
Uat7ivEIDOHxrgXCa9Cncmqp/YWqfau9X3fSM0ITYtg+fFrRPVG2m0I+wRPkWBcW
yBfLrV0rXsa0foWUKmWkAl48Ae5sd7ZfkCyFTUlJSpf9kJEZGyVzzZ2+cJmKO0Ts
Ci70IwOU5Z5q7kTfORN0r8uQbEhaha4rfEHT7h/wKON8dApKEGkThYwem797KLG8
l/y/YL2WeiaoCvyiUZkJTzXo8szN9TZ+9lL8wTCv0IV+8Ku397L8D+nX2m5Me0+T
rRgL1cECgYEA2KGUAXesH8YtYn8iXEdgs/kUNWB/yh6XKT5z149kK89qj9M6DMER
ZvM4gyMqHbzzSPeXgStzDFpL74CLKgoGM+7fJdMarpmn47PAuEwfdrZrp3dfv4FY
SCakNieFxxDEBx7Dixg/WYK3zVEc2HuFxP9S22fbG5pJqMdzAoxrHP0CgYEAw41X
e8DV3TqW4EkCv1Ia12KwkhJ+Hc1Mtoq16oqmXNOCCDiAusfPSmV5OkicWhqSKT7b
2YU2ntur3SZpbE/CO7oIQ7y5WCqzdIIbfFWACGewuTbicIgZskWugfg5Cm4HZHU+
bswar/SU0wlOq+rsGBEMZoTHWNKDgCkiEfLIYpkCgYBSA/pxA8nazcDpBZEQtsVZ
3fdUrhAziZlZOBfLiLI4E/MYOLRFl+LqwzKmE24ijCLfgT2O3dBU4OrW5ifJ5rmq
d8lsHJyBdhMmFivkmo1e+HmRkZRigKnXxylsh1ISp7pPp8oIo0l4rhDAXvE41Ks0
hiXsg0llDejNSZW1ZUw4UQKBgDAnO+WjZIZniQONtvl5tFZZXPV7TEb2aO+CVK1+
mty80IZJs5lK3oaJWkK54pZleJxjeCumG/8eRMHeVZNwSHoAo0xayqElUq9b4nzJ
aQ+oyOGy3Cutt/YoJoKmpxUkZvmbzVXnOKMfbQ5SembnIGAEwrLz+0qp++uxNUyK
JkHxAoGAUSBdmcmK13D6uLYFeggO47PjbkpzGatC4tmIqukKYWqfuQEKI3iKMdTv
u3strsV2oSrB3yVvDvzIwLzNACkyJbNPZ6tF+DxnLCVE2fNsPPL4mp7i4DZp+3en
3qOP4OZSYN4hX+g7OUvQ8zdptCFwEyXto+NSvZtNp7itLF7B4oM=
-----END RSA PRIVATE KEY-----

--------------------------
10) Add required GitHub secrets
In GitHub:

Settings → Secrets and variables → Actions
Add:

AWS_ROLE_ARN
EC2_HOST
EC2_USERNAME
EC2_SSH_KEY


---------------------------
11) Push to GitHub to trigger deployment
git add .
git commit -m "Deploy app to EC2 with Docker and GitHub Actions"
git push origin main

Then GitHub Actions will:

run tests
build Docker image
push to ECR
SSH into EC2
pull image
run container


---------------------------
12) Validate on EC2
Check running container:

docker ps

Check health endpoint:

If actuator is not enabled, use:
curl http://<EC2_PUBLIC_IP>:8586

Then test transfer endpoint:
curl -X POST "http://<EC2_PUBLIC_IP>:8586/api/v1/transfer?overwrite=false"



---------------------------
13) Make sure S3 → Azure transfer works
Your app will work only if all of these are true:

S3
bucket exists
IAM role/user has permission to read and delete objects
files are present in the bucket
Azure
storage account exists
container exists
account key is valid
app has the right Azure connection config
Database
RDS MySQL is reachable from EC2
secret contains valid mysql_url
username/password are correct
App config
use RDS endpoint instead of localhost
port is 8586
secret name matches s3-to-azure/credentials

---------------------------
14) Recommended final AWS secret
Use this final structure for your app:

{
  "aws_access_key_id": "",
  "aws_secret_access_key": "",
  "s3_bucket_name": "aileen-bucket",
  "s3_region": "us-east-1",
  "azure_storage_account_name": "s3azureconnector123",
  "azure_storage_account_key": "YOUR_AZURE_KEY",
  "azure_container_name": "s3-azure-connector",
  "mysql_username": "admin",
  "mysql_password": "StrongPassword123",
  "mysql_url": "jdbc:mysql://database-1.cxyz123.us-east-1.rds.amazonaws.com:3306/filetransfer?createDatabaseIfNotExist=true&useSSL=true&serverTimezone=UTC&allowPublicKeyRetrieval=true"
}



---------------------------
15) Final advice
This is the exact deployment path that will make your app work properly:

EC2 runs the Spring Boot container
RDS stores the app database
Secrets Manager stores the app config
GitHub Actions does CI/CD
S3 is the source
Azure Blob is the destination
The one thing you must not miss is replacing localhost in the datasource config. Without that, the app cannot run on EC2.

If you want, I can give you the next step as a ready-to-copy package:

exact DataSourceConfig Java code,
exact AWS IAM policy,
exact final deploy.yml,
and exact EC2 shell commands for one-click setup.





----------------

terraform

├── provider.tf
├── variables.tf
├── vpc.tf
├── iam.tf
├── ecr.tf
├── ec2.tf
├── rds.tf
├── secrets.tf
├── outputs.tf
└── terraform.tfvars



-------------------------
provider.tf
Configures the AWS provider and minimum Terraform version requirements.

 terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}
------------------------
2. variables.tf
Declares input variables used across the modules.

variable "aws_region" {
  description = "AWS region for deployment"
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Project name prefix for resource naming"
  type        = string
  default     = "filetransfer"
}

variable "environment" {
  description = "Environment name (dev, staging, prod)"
  type        = string
  default     = "dev"
}

variable "github_repository" {
  description = "GitHub repository formatted as 'username/repo-name'"
  type        = string
  default     = "PhuocQuang76/TransferFile_S3_Azure"
}

variable "ec2_instance_type" {
  description = "EC2 instance size"
  type        = string
  default     = "t3.small"
}

variable "db_instance_class" {
  description = "RDS instance class"
  type        = string
  default     = "db.t3.micro"
}

variable "db_allocated_storage" {
  description = "RDS allocated storage in GB"
  type        = number
  default     = 20
}

variable "db_name" {
  description = "Database name"
  type        = string
  default     = "filetransfer"
}

variable "db_username" {
  description = "Database master username"
  type        = string
  default     = "admin"
}

variable "db_password" {
  description = "Database master password"
  type        = string
  sensitive   = true
}

variable "azure_storage_account_key" {
  description = "Azure Storage Account Key"
  type        = string
  sensitive   = true
}

variable "allowed_ssh_cidr" {
  description = "CIDR block permitted to SSH into EC2"
  type        = string
  default     = "0.0.0.0/0"
}

variable "allowed_api_cidr" {
  description = "CIDR block permitted to access Spring Boot API (port 8586)"
  type        = string
  default     = "0.0.0.0/0"
}
------------------------
3. vpc.tf
Creates a dedicated VPC with public subnets, internet gateway, route tables, and security groups for EC2 and RDS.

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true
  enable_dns_support   = true

  tags = {
    Name        = "${var.project_name}-${var.environment}-vpc"
    Environment = var.environment
  }
}

resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name        = "${var.project_name}-${var.environment}-igw"
    Environment = var.environment
  }
}

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.${count.index}.0/24"
  availability_zone       = data.aws_availability_zones.available.names[count.index]
  map_public_ip_on_launch = true

  tags = {
    Name        = "${var.project_name}-${var.environment}-public-${count.index}"
    Environment = var.environment
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = {
    Name        = "${var.project_name}-${var.environment}-public-rt"
    Environment = var.environment
  }
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

# EC2 Security Group
resource "aws_security_group" "ec2" {
  name        = "${var.project_name}-${var.environment}-ec2-sg"
  description = "Security group for application EC2 instance"
  vpc_id      = aws_vpc.main.id

  ingress {
    description = "SSH Access"
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = [var.allowed_ssh_cidr]
  }

  ingress {
    description = "Spring Boot Application API"
    from_port   = 8586
    to_port     = 8586
    protocol    = "tcp"
    cidr_blocks = [var.allowed_api_cidr]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name        = "${var.project_name}-${var.environment}-ec2-sg"
    Environment = var.environment
  }
}

# RDS Security Group
resource "aws_security_group" "rds" {
  name        = "${var.project_name}-${var.environment}-rds-sg"
  description = "Security group for RDS MySQL database"
  vpc_id      = aws_vpc.main.id

  ingress {
    description     = "MySQL port from EC2 Security Group only"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.ec2.id]
  }

  tags = {
    Name        = "${var.project_name}-${var.environment}-rds-sg"
    Environment = var.environment
  }
}

------------------------
4. iam.tf
Configures:

EC2 IAM Role: Grants reading capabilities for Secrets Manager, S3, and ECR directly to the instance.

GitHub OIDC Provider & IAM Role: Configures keyless authentication for GitHub Actions to manage and push Docker images to ECR.


# --- 1. EC2 Instance Role & Profile ---
resource "aws_iam_role" "ec2" {
  name = "${var.project_name}-${var.environment}-ec2-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "ec2_policies" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
    "arn:aws:iam::aws:policy/AmazonS3ReadOnlyAccess",
    "arn:aws:iam::aws:policy/SecretsManagerReadWrite"
  ])
  role       = aws_iam_role.ec2.name
  policy_arn = each.value
}

resource "aws_iam_instance_profile" "ec2" {
  name = "${var.project_name}-${var.environment}-ec2-profile"
  role = aws_iam_role.ec2.name
}

# --- 2. GitHub Actions OIDC Role ---
data "tls_certificate" "github" {
  url = "https://token.actions.githubusercontent.com/.well-known/openid-configuration"
}

resource "aws_iam_openid_connect_provider" "github" {
  url             = "https://token.actions.githubusercontent.com"
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.github.certificates[0].sha1_fingerprint]
}

resource "aws_iam_role" "github_actions" {
  name = "GitHubActionsECRRole"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Action    = "sts:AssumeRoleWithWebIdentity"
      Principal = { Federated = aws_iam_openid_connect_provider.github.arn }
      Condition = {
        StringEquals = { "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com" }
        StringLike   = { "token.actions.githubusercontent.com:sub" = "repo:${var.github_repository}:*" }
      }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "github_ecr" {
  role       = aws_iam_role.github_actions.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryPowerUser"
}

------------------------
5. ecr.tf
Creates the ECR repository named file-transfer-service along with an automated image retention lifecycle policy.'

resource "aws_ecr_repository" "file_transfer" {
  name                 = "file-transfer-service"
  image_tag_mutability = "MUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }

  tags = {
    Name        = "${var.project_name}-${var.environment}-ecr"
    Environment = var.environment
  }
}

resource "aws_ecr_lifecycle_policy" "main" {
  repository = aws_ecr_repository.file_transfer.name

  policy = jsonencode({
    rules = [
      {
        rulePriority = 1
        description  = "Retain the last 10 container images"
        selection = {
          tagStatus   = "any"
          countNumber = 10
          countType   = "imageCountMoreThan"
        }
        action = {
          type = "expire"
        }
      }
    ]
  })
}

------------------------
6. ec2.tf
Deploys the Amazon Linux 2023 EC2 instance with the attached key pair and IAM profile.

data "aws_ami" "amazon_linux_2023" {
  most_recent = true
  owners      = ["amazon"]

  filter {
    name   = "name"
    values = ["al2023-ami-2023.*-x86_64"]
  }
}

resource "aws_key_pair" "main" {
  key_name   = "${var.project_name}-${var.environment}-key"
  public_key = file("${path.module}/ec2_public_key.pub")

  tags = {
    Name        = "${var.project_name}-${var.environment}-key"
    Environment = var.environment
  }
}

resource "aws_instance" "main" {
  ami                    = data.aws_ami.amazon_linux_2023.id
  instance_type          = var.ec2_instance_type
  subnet_id              = aws_subnet.public[0].id
  vpc_security_group_ids = [aws_security_group.ec2.id]
  iam_instance_profile   = aws_iam_instance_profile.ec2.name
  key_name               = aws_key_pair.main.name

  tags = {
    Name        = "${var.project_name}-${var.environment}-ec2"
    Environment = var.environment
  }
}

------------------------
7. rds.tf
Creates an RDS MySQL instance with a dedicated subnet group, parameter group, and security group.

resource "aws_db_subnet_group" "main" {
  name       = "${var.project_name}-${var.environment}-subnet-group"
  subnet_ids = aws_subnet.public[*].id

  tags = {
    Name        = "${var.project_name}-${var.environment}-subnet-group"
    Environment = var.environment
  }
}

resource "aws_db_parameter_group" "main" {
  name   = "${var.project_name}-${var.environment}-pg"
  family = "mysql8.0"

  parameter {
    name  = "max_connections"
    value = "100"
  }

  parameter {
    name  = "character_set_server"
    value = "utf8mb4"
  }

  parameter {
    name  = "collation_server"
    value = "utf8mb4_unicode_ci"
  }
}

resource "aws_db_instance" "main" {
  identifier = "${var.project_name}-${var.environment}-db"

  engine         = "mysql"
  engine_version = "8.0"
  instance_class = var.db_instance_class

  allocated_storage = var.db_allocated_storage
  storage_type      = "gp2"
  storage_encrypted = true

  db_name  = var.db_name
  username = var.db_username
  password = var.db_password

  db_subnet_group_name   = aws_db_subnet_group.main.name
  vpc_security_group_ids = [aws_security_group.rds.id]

  publicly_accessible     = false
  skip_final_snapshot     = true
  backup_retention_period = 7
  backup_window           = "03:00-04:00"
  maintenance_window      = "Mon:04:00-Mon:05:00"

  parameter_group_name = aws_db_parameter_group.main.name

  tags = {
    Name        = "${var.project_name}-${var.environment}-rds"
    Environment = var.environment
  }
}

------------------------


8. secrets.tf
Stores application configurations and constructs the JDBC connection string pointing to the generated RDS MySQL endpoint.

resource "aws_secretsmanager_secret" "app_config" {
  name = "s3-to-azure/credentials"

  tags = {
    Name        = "${var.project_name}-${var.environment}-app-config"
    Environment = var.environment
  }
}

resource "aws_secretsmanager_secret_version" "app_config" {
  secret_id = aws_secretsmanager_secret.app_config.id

  secret_string = jsonencode({
    aws_access_key_id          = ""
    aws_secret_access_key      = ""
    s3_bucket_name             = "aileen-bucket"
    s3_region                  = var.aws_region
    azure_storage_account_name = "s3azureconnector123"
    azure_storage_account_key  = var.azure_storage_account_key
    azure_container_name       = "s3-azure-connector"
    mysql_username             = var.db_username
    mysql_password             = var.db_password
    mysql_url                  = "jdbc:mysql://${aws_db_instance.main.endpoint}/${var.db_name}?createDatabaseIfNotExist=true&useSSL=true&serverTimezone=UTC&allowPublicKeyRetrieval=true"
  })

  depends_on = [aws_db_instance.main]
}

------------------------
9. outputs.tf
Prints values required for GitHub Actions pipeline secrets and administrative access.

output "ec2_public_ip" {
  description = "Public IP of the EC2 instance (Set as EC2_HOST in GitHub Secrets)"
  value       = aws_instance.main.public_ip
}

output "rds_endpoint" {
  description = "Endpoint address of the RDS database"
  value       = aws_db_instance.main.endpoint
}

output "ecr_repository_url" {
  description = "ECR Repository URI"
  value       = aws_ecr_repository.file_transfer.repository_url
}

output "github_actions_role_arn" {
  description = "IAM Role ARN for GitHub Actions (Set as AWS_ROLE_ARN in GitHub Secrets)"
  value       = aws_iam_role.github_actions.arn
}

output "secret_arn" {
  description = "Secrets Manager Secret ARN"
  value       = aws_secretsmanager_secret.app_config.arn
}

------------------------
10. terraform.tfvars
Fill in local sensitive variable values before deployment. Keep this file out of source control (.gitignore).


db_password               = "StrongPassword123!"
azure_storage_account_key = "YOUR_AZURE_STORAGE_KEY_HERE"
github_repository         = "PhuocQuang76/TransferFile_S3_Azure"








---------------------------***********------------------------
terraform init
-> create key pair
terraform plan
terraform apply -auto-approve



---------------------------***********------------------------
Create key pair for EC2 instance
ssh-keygen -t rsa -b 4096 -f ec2_public_key -N ""

then terraform apply
 
Then connect
# 1. Set correct read permissions on your private key (required by SSH)
chmod 400 ec2_public_key

# 2. SSH into your EC2 instance using the public IP from Terraform output
ssh -i ec2_public_key ec2-user@<YOUR_EC2_PUBLIC_IP>





---------------------------***********------------------------
### Step-by-Step Next Actions ###

1. Set File Permissions on Your Private Key
Set strict read-only permissions on your local private key (SSH will reject key files that are world-readable):

chmod 400 ec2_public_key

---------------------------

2. Test SSH Connection to EC2
Connect directly to your newly created server using the public IP provided in your outputs (35.174.11.219):

ssh -i ec2_public_key ec2-user@23.22.237.173

When prompted Are you sure you want to continue connecting (yes/no/[fingerprint])?, type yes and press Enter.

---------------------------

3. Update Your GitHub Repository Secrets
Get values first:
-----
# 1. AWS Role ARN
terraform output -raw github_actions_role_arn
echo ""

# 2. EC2 Public Host IP
terraform output -raw ec2_public_ip
echo ""

# 3. Private SSH Key
cat ec2_public_key

----------
To enable keyless deployment from GitHub Actions to ECR/EC2, navigate to your GitHub Repository:
Settings → Secrets and variables → Actions and configure the following:

Secret / Variable Name,  Type,      Value
aws_access_key_id        Secret     ""
aws_secret_access_key    Secret     ""
AWS_ROLE_ARN             Secret     arn:aws:iam::364218292370:role/GitHubActionsECRRole
EC2_HOST                 Secret     35.174.11.219
EC2_USERNAME             Secret     ec2-user
EC2_SSH_KEY              Secret     Copy & paste the complete contents of your local ec2_public_key file


4. Verify Secrets Manager Contents (Optional)
To verify that Terraform correctly populated your database connection strings and credentials into Secrets Manager, run this AWS CLI command in your terminal:

aws secretsmanager get-secret-value --secret-id s3-to-azure/credentials --query SecretString --output text


---------------------------
BASH file to cretae github secrets

1. Create the bash file
nano setup-github-secrets.sh

2. Paste the contents into it:
#!/usr/bin/env bash

# Set your repository name
REPO="PhuocQuang76/TransferFile_S3_Azure"

# 1. Upload AWS Role ARN
gh secret set AWS_ROLE_ARN --repo "$REPO" --body "$(terraform output -raw github_actions_role_arn)"

# 2. Upload EC2 Public Host / IP
gh secret set EC2_HOST --repo "$REPO" --body "$(terraform output -raw ec2_public_ip)"

# 3. Upload EC2 Username
gh secret set EC2_USERNAME --repo "$REPO" --body "ec2-user"

# 4. Upload Private SSH Key content
gh secret set EC2_SSH_KEY --repo "$REPO" < ec2_public_key

--------
3. Make the bash file executable
chmod +x setup-github-secrets.sh

---------
4. Run the bash file
./setup-github-secrets.sh



---------------------------***********------------------------
ANSIBLE

Command line
ansible-playbook -i inventory.ini setup-docker.yml


---------------------------***********------------------------
Check
1. docker container
docker ps -a

2. health check
curl http://34.229.135.146:8586/actuator/health



---------------------------***********------------------------
TEST transfer
crete file in s3
echo "Hello, this is a test file for S3 to Azure transfer!" > test-file1.txt


[app_server]
ec2_instance ansible_host=3.91.43.130 ansible_user=ec2-user ansible_ssh_private_key_file=../terraform/ec2_public_key

THen run command to transfer
# Option 1: If your transfer endpoint uses a POST mapping
curl -X POST http://34.229.135.146:8586/api/transfer/start

# Option 2: If your transfer endpoint is mapped directly 
curl -X POST http://34.229.135.146:8586/transfer




--------------***************----------------
AWS vs GITHUB connection
IAM colsole
Click on Idemtity Provider
    add provider
    Select OpenID Connect as the provider type and fill out the form:
        - Provider URL: https://token.actions.githubusercontent.com
        - Click the Get thumbprint button to let AWS automatically fetch GitHub's active SSL certificate thumbprint (replacing the Terraform tls_certificate data block).
        - Audience: sts.amazonaws.com

    Add the Provider: Finalization. Click Add provider. AWS will create the OIDC provider record, matching the resource configuration defined in your code.

IN TERRAFORM
  1) Fetches GitHub's SSL certificate thumbprint to establish trust with AWS

  2) Registers GitHub as an OpenID Connect (OIDC) identity provider in your AWS account

  3) This specific role (GitHubActionsECRRole) is for your CI/CD pipeline (GitHub Actions).

Its sole purpose is to allow your automated GitHub builds to securely log into AWS and push or pull Docker container images to your AWS ECR (Elastic Container Registry) without using any permanent passwords or secret access keys.

When your code triggers a build on GitHub:

GitHub requests a temporary OIDC token.

AWS checks that token against this role's Trust Policy (verifying it is coming from your exact repository and main branch).

Once verified, AWS grants temporary permission to use the attached ECR PowerUser policy so your Docker images can be uploaded successfully.