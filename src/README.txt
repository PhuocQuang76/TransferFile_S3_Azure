
*** AZURE SET UP ***

azure:
  storage:
    account-name: s3azureconnector123
    container-name: s3-azure-connector
    connection-string: <YOUR_PRIMARY_CONNECTION_STRING>

*** S3 SET UP ***
* Create bucket name "
aileen-bucket"

* Create a batch policy for the S3 bucket
---
cat << 'EOF' > s3-policy.json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowS3BucketListing",
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": "arn:aws:s3:::aileen-bucket"
    },
    {
      "Sid": "AllowS3ObjectReadAndDelete",
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:DeleteObject"],
      "Resource": "arn:aws:s3:::aileen-bucket/*"
    }
  ]
}
EOF
----

* set policy for IAM_user to allow access to S3 bucket
aws iam put-user-policy \
  --user-name cloud_user \
  --policy-name S3TransferPolicy \
  --policy-document file://s3-policy.json

--
* Test to push file to s3 bucket
echo "Testing Spring Boot S3 to Azure transfer" > sample.txt
aws s3 cp sample.txt s3://aileen-bucket/sample.txt


*** SECRET MANAGER ***

aws:
  secrets:
    secret-name: s3-to-azure/credentials
    region: us-east-1



------------------
* Set Up Secret Manager

aws secretsmanager create-secret \
    --name s3-to-azure/credentials\
    --description "Credentials and configuration for transferring S3 data to Azure Blob Storage" \
    --secret-string '{
      "aws_access_key_id": "",
      "aws_secret_access_key": "",
      "s3_bucket_name": "aileen-bucket",
      "s3_region": "us-east-1",
      "azure_storage_account_name": "s3azureconnector123",
      "azure_storage_account_key": "D",
      "azure_container_name": "s3-azure-connector",
      "mysql_username": "root",
      "mysql_password": "admin"
    }' \
    --region us-east-1

** UPDATE
aws secretsmanager update-secret \
    --secret-id "s3-to-azure/credentials_v1" \
    --secret-string '{
      "aws_access_key_id": "",
      "aws_secret_access_key": "",
      "s3_bucket_name": "aileen-bucket",
      "s3_region": "us-east-1",
      "azure_storage_account_name": "s3azureconnector123",
      "azure_storage_account_key": "",
      "azure_container_name": "s3-azure-connector",
      "mysql_username": "root",
      "mysql_password": "admin"
    }' \
    --region us-east-1


* Secret
+-----------------------------------------------------------------------------------+
| 1. Local Machine (~/.aws/credentials)                                              |
|    Reads Access Key & Secret Key                                                  |
+-----------------------------------------------------------------------------------+
                                  |
                                  v
+-----------------------------------------------------------------------------------+
| 2. SecretsManagerClient (AWS SDK)                                                 |
|    Signs request & sends HTTPS POST to secretsmanager.us-east-1.amazonaws.com     |
+-----------------------------------------------------------------------------------+
                                  |
                                  v
+-----------------------------------------------------------------------------------+
| 3. AWS Cloud (Secrets Manager)                                                    |
|    Validates IAM permissions -> Fetches secret -> Returns JSON String             |
+-----------------------------------------------------------------------------------+
                                  |
                                  v
+-----------------------------------------------------------------------------------+
| 4. Jackson ObjectMapper (Spring Boot)                                             |
|    Parses JSON String -> Map<String, String> Bean available across the app        |
+-----------------------------------------------------------------------------------+




HOW IT WORKS

upload files to s3 bucket

echo "test file 1" > test1.txt
aws s3 cp test1.txt s3://aileen-bucket/test1.txt

echo "test file 2"  > test2.txt
aws s3 cp test2.txt s3://aileen-bucket/test2.txt

TEST TRANSFER
curl -X POST "http://localhost:8586/api/v1/transfer?overwrite=false"




***** SOLID *****
1. Single Responsibility Principle (SRP)
SecretsManagerConfig: Only secrets parsing
AWSConfig: Only AWS client creation
AzureConfig: Only Azure client creation
S3Service: Only S3 operations
AzureBlobService: Only Azure operations
TransferService: Only transfer orchestration

2. Open/Closed Principle (OCP)
Add new storage providers (GCS, MinIO, etc.) without modifying TransferService
Just implement StorageSource/StorageDestination interfaces
TransferConfig selects implementations via configuration

3. Liskov Substitution Principle (LSP)
S3Service and any future implementations can substitute for StorageSource
AzureBlobService and any future implementations can substitute for StorageDestination
Interface contracts are maintained

4. Interface Segregation Principle (ISP)
StorageSource and StorageDestination are separate, focused interfaces
Each service only implements methods it needs
No fat interfaces with unused methods

5. Dependency Inversion Principle (DIP)
TransferService depends on interfaces (StorageSource, StorageDestination), not concrete classes
Config classes provide abstractions via dependency injection
Easy to swap implementations without changing high-level modules


->
"1. High-level modules should not depend on low-level modules. Both should depend on abstractions."
"2. Abstractions should not depend on details. Details should depend on abstractions."
High-level logic (business rules) should never directly instantiate or rely on low-level details (databases, cloud SDKs, specific frameworks). They should interact through interfaces.


-----------------------
Besides SOLID, design principles and patterns:

1. Dependency Injection (DI) & Inversion of Control (IoC)

Spring Framework manages object creation and lifecycle
Constructor injection in all services
@Bean methods in config classes
@Qualifier for selecting specific beans
2. Reactive Programming

Project Reactor (Mono, Flux) for non-blocking I/O
Asynchronous file operations without blocking threads
Backpressure handling for large file transfers
3. Strategy Pattern

StorageSource and StorageDestination as strategy interfaces
Different implementations (S3, Azure) as concrete strategies
Runtime selection via configuration
4. Factory Pattern

AWSConfig, AzureConfig, SecretsManagerConfig act as factories
Centralize client creation logic
Encapsulate complex initialization
5. Facade Pattern

TransferService acts as a facade
Simplifies complex multi-step transfer process
Hides implementation details from consumers
6. Builder Pattern

AWS SDK uses builders: S3AsyncClient.builder(), SecretsManagerClient.builder()
Azure SDK uses builders: BlobServiceClientBuilder()