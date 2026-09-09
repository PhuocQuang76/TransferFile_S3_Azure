
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



----------------


*** UNIT TEST ***
using **LocalStack** (fake S3) and **Azurite** (fake Azure Blob) so no real cloud account is needed.


Step 1: Add Test Dependencies to pom.xml
<!-- Testcontainers Base -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>testcontainers</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>junit-jupiter</artifactId>
        <scope>test</scope>
    </dependency>

    <!-- LocalStack Module -->
    <dependency>
        <groupId>org.testcontainers</groupId>
        <artifactId>localstack</artifactId>
        <scope>test</scope>
    </dependency>



Start Cloud Emulators Locally
1. Start LocalStack (AWS S3)
Run LocalStack using Docker CLI (or Docker Desktop) mapping port 4566:

Bash
docker run --rm -d -p 4566:4566 -p 4510-4559:4510-4559 localstack/localstack:3.0.0


2. Start Azurite (Azure Blob Storage)
You can run Azurite via npm or via Docker:

Option A: Via npm
Bash
npm install -g azurite
azurite --blobPort 10000 --queuePort 10001 --tablePort 10002

Option B: Via Docker
Bash
docker run --rm -d -p 10000:10000 mcr.microsoft.com/azure-storage/azurite azurite-blob --blobHost 0.0.0.0


Step 2: Add application-test.properties
Create src/test/resources/application-test.properties to direct your application to your local emulators:

# AWS S3 Settings (LocalStack)
aws.s3.endpoint=http://localhost:4566
aws.s3.region=us-east-1
aws.s3.access-key=test
aws.s3.secret-key=test
storage.source-bucket=test-bucket

# Azure Blob Settings (Azurite)
azure.storage.endpoint=http://localhost:10000
azure.storage.account-name=devstoreaccount1
azure.storage.account-key=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==
storage.destination-bucket=test-container


Step 3: Pre-Create Buckets and Containers
Before running the test, the bucket (test-bucket) and container (test-container) must exist inside the running emulators.

Using AWS CLI and Azure CLI (or cURL/scripts):

# Create S3 Bucket in LocalStack
aws --endpoint-url=http://localhost:4566 s3 mb s3://test-bucket --region us-east-1

# Create Azure Blob Container in Azurite
azstorage container create --name test-container \
  --connection-string "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;"




Step 4: Create Integration Test Class

----------------------------------------------------------------

[ HTTP Request ] ──► TransferControllerTest (Unit)
                      • Tests URL mapping, Query Params, HTTP Status, JSON formatting.
                      • Mocked: TransferService is fake.
                            │
                            ▼
                     TransferServiceTest (Unit)
                      • Tests Business Rules, Skip/Overwrite logic, Reactive Streams, Error codes.
                      • Mocked: Cloud Storage SDKs are fake.
                            │
                            ▼
                     FileTransferIntegrationTest (Integration)
                      • Tests Real WebFlux + Real Service + Real Docker Emulators (LocalStack/Azurite).
                      • Mocked: Nothing.
--------------------------------------------------------------------



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


6. Builder Pattern
AWS SDK uses builders: S3AsyncClient.builder(), SecretsManagerClient.builder()
Azure SDK uses builders: BlobServiceClientBuilder()




--------------------------------------------
*** IDEMPOTENCY *** 

Idempotency (lines 67-69 in TransferService):

Mono<Boolean> shouldSkipMono = overwrite ?
        Mono.just(false) :
        destination.existsAndMatchesSize(filePath, fileSize);
Checks if file already exists in destination with matching size
If yes, skips upload (SKIPPED status)
Re-running the transfer won't duplicate files
Retry (lines 107-109 in AzureBlobService):


.retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
        .doBeforeRetry(signal -> log.warn("Retrying upload for [{}] (Attempt {}/3) due to: {}",
                blobName, signal.totalRetries() + 1, signal.failure().getMessage())))
Azure upload retries up to 3 times with 2-second backoff on network failures
Only applies to Azure upload, not S3 operations
Network down scenario:

Network fails during upload → Azure retries 3 times
If all retries fail → file marked as FAILED
Other files continue processing (job doesn't stop)
Re-run transfer → already-transferred files are skipped (idempotency)
Limitations:

Retry only on Azure upload side
No retry on S3 operations (list, get metadata, stream, delete)
If network fails during S3 operations, file immediately fails





--------------------------------------------
**** MONO AND FLUX ****
Mono: Processes everything in the background, waits until all work is done, gathers the results, and returns one single combined result (like a summary or a list) at the end.

Flux: Processes files in the background and streams each result back immediately, one by one, as soon as each individual file finishes.

Quick Code Comparison
Notice how tiny the code change is between the two in Spring WebFlux:

1. Returning Mono (Batch / Wait Mode)
Java
public Mono<TransferSummary> executeTransfer(...) {
    return source.listObjects(prefix, extension)
            .flatMap(file -> processSingleFile(file.getKey(), file.getSize(), overwrite), concurrencyLimit)
            .collectList() // <--- Gathers all individual results into a single list
            .map(results -> buildSummary(results, startTime)); // <--- Bundles into 1 Mono summary
}
2. Returning Flux (Real-Time Streaming Mode)
Java
public Flux<TransferResult> executeTransferStream(...) {
    return source.listObjects(prefix, extension)
            .flatMap(file -> processSingleFile(file.getKey(), file.getSize(), overwrite), concurrencyLimit);
            // No collectList()! Emits each TransferResult immediately upon completion
}
When to Use Which?
Use Mono when your application or REST client only cares about the final status (e.g., an automated batch job or a basic REST API returning 200 OK with a summary report).

Use Flux when you want live status updates (e.g., sending live progress to a UI dashboard via Server-Sent Events) or when handling huge amounts of data without overloading memory.


EXPLAIN
1. Request Initiated
   Thread-1 starts executeTransfer()
   │
   ├─► Registers async listeners for all file operations
   │
2. Thread FREED Immediately!
   Thread-1 leaves the method and goes back to handling other API calls/tasks.
   (0 Java threads are running or blocked right now while files transfer!)
   │
3. Non-blocking Network I/O
   The Operating System and Netty Event Loop stream network packets in the background.
   │
4. As files finish...
   As each file upload completes, an OS event fires.
   React gathers each result internally into a non-blocking collector.
   │
5. Final File Finishes!
   Reactor picks ANY available worker thread from its pool.
   This thread runs buildSummary(...) and emits the single Mono<TransferSummary>!




--------------------------------------------

what is challenge you face?

Challenge 2: Idempotency - Preventing Duplicate Transfers
Problem: Re-running the transfer job would duplicate files in destination
Solution: Implemented existsAndMatchesSize() in AzureBlobService to check if file already exists with matching size before upload
Result: Safe re-runs, files are skipped if already transferred


Challenge 3: Dynamic Provider Selection (Open/Closed Principle)
Problem: Hardcoding S3 and Azure services made it difficult to add new storage providers
Solution: Used Map-based injection in TransferConfig with @Value properties to select implementations at runtime
Result: Can add GCS or MinIO without modifying TransferService code


Challenge 4: Network Resilience
Problem: Network hiccups during Azure upload would cause permanent failures
Solution: Added retryWhen(Retry.backoff(3, Duration.ofSeconds(2))) in AzureBlobService upload
Result: Automatic retry with exponential backoff for transient network issues
--------------------------------------------




------------------------ RUN ON LOCAL ----------------------------


RUN THE APPLICATION:

mvn spring-boot:run


HOW IT WORKS

upload files to s3 bucket

echo "test file 1" > test1.txt
aws s3 cp test1.txt s3://aileen-bucket/test1.txt

echo "test file 2"  > test2.txt
aws s3 cp test2.txt s3://aileen-bucket/test2.txt

TEST TRANSFER
curl -X POST "http://localhost:8586/api/v1/transfer?overwrite=false"

# Filter by folder
curl -X POST "http://localhost:8586/api/v1/transfer?prefix=invoices/"
 
# Filter by file type
curl -X POST "http://localhost:8586/api/v1/transfer?extension=.pdf"

# Combine both
curl -X POST "http://localhost:8586/api/v1/transfer?prefix=invoices/&extension=.pdf"


----------------------   RUN ON AWS  ----------------------

Local Machine (Git Push) ──► GitHub Repository
                                  │
                       Triggers GitHub Actions
                                  │
             ┌────────────────────┴────────────────────┐
             ▼                                         ▼
1. Run Unit Tests                        2. Build Docker Image
   (mvn test)                                  │
                                         Authenticate via OIDC
                                               │
                                         Push to Amazon ECR
                                               │
                                         SSH into Amazon EC2
                                               │
                                         Pull & Run Container



*****  Phase 1: Set Up AWS Infrastructure ********

Step 1: Create an Amazon ECR Repository
Run this command on your local terminal using the AWS CLI:

aws ecr create-repository --repository-name file-transfer-service --region us-east-1
Note: Note down the repository URI returned in the terminal (e.g., <ACCOUNT_ID>[.dkr.ecr.us-east-1.amazonaws.com/file-transfer-service](https://.dkr.ecr.us-east-1.amazonaws.com/file-transfer-service)).


Step 2: Configure GitHub OIDC in AWS IAM
Instead of storing permanent access keys in GitHub, set up an OpenID Connect (OIDC) identity provider and role.
[ Your Laptop ] ──► [ GitHub ] ──► [ GitHub Actions ] ──► [ AWS ECR ] ──► [ AWS EC2 ]
  (Write Code)       (Storage)      (The Robot Worker)    (Box Store)     (The Live Kitchen)
1. Go to AWS IAM Console -> Identity Providers -> Add Provider.

Provider Type: OpenID Connect

Provider URL: [https://token.actions.githubusercontent.com](https://token.actions.githubusercontent.com)

Audience: sts.amazonaws.com

2. Go to IAM Roles -> Create Role.

Select Custom Trust Policy and paste:

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

(Replace <ACCOUNT_ID>, <YOUR_GITHUB_USERNAME>, and <YOUR_REPO_NAME> with your actual details).

3. Attach the permission policy AmazonEC2ContainerRegistryPowerUser to this role.

4. Name the role GitHubActionsECRRole and save its ARN.

Step 3: Launch and Configure the EC2 Instance
Go to AWS EC2 Console -> Launch Instance.

Name: file-transfer-ec2

AMI: Amazon Linux 2023

Instance Type: t3.medium (or t3.small)

Key Pair: Create or select an existing key pair (.pem file). Save this key on your computer.

Security Group Rules:

Allow SSH (Port 22) from your IP.

Allow Custom TCP (Port 8080) from 0.0.0.0/0.


2. Attach IAM Role to EC2:

Create an IAM Role for EC2 with AmazonEC2ContainerRegistryReadOnly policy attached.

Attach it to your EC2 instance under Actions -> Security -> Modify IAM Role.

3. Install Docker on EC2:
Connect to your instance via SSH from your machine:

ssh -i /path/to/your-key.pem ec2-user@<EC2_PUBLIC_IP>

Run the setup commands on the instance:

sudo dnf update -y
sudo dnf install -y docker
sudo systemctl start docker
sudo systemctl enable docker
sudo usermod -aG docker ec2-user
Log out of EC2 (exit).


****** Phase 2: Configure Your Local Repository ******
Step 4: Add Dockerfile to Project Root
In your Spring Boot project root on your machine, create a file named Dockerfile:

# Stage 1: Build the artifact
Step 4: Add Dockerfile to Project Root
In your Spring Boot project root on your machine, create a file named Dockerfile:

---
# Stage 1: Build the artifact
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /app
COPY . .
RUN ./mvnw clean package -DskipTests

# Stage 2: Minimal runtime image
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
---

Step 5: Configure GitHub Secrets
In your GitHub repository web browser:

Navigate to Settings -> Secrets and variables -> Actions.

Add four repository secrets:
Secret Name,      Value
AWS_ROLE_ARN,     The ARN of GitHubActionsECRRole created in Step 2
EC2_HOST,         The Public IP address of your EC2 instance
EC2_USERNAME,     ec2-user
EC2_SSH_KEY,      Entire content of your .pem SSH key file


Step 6: Create the GitHub Actions Workflow File
In your local project directory, create .github/workflows/deploy.yml:

---
name: CI/CD Pipeline to AWS EC2

on:
  push:
    branches: [ "main" ]

permissions:
  id-token: write
  contents: read

jobs:
  test-build-deploy:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout Code
        uses: actions/checkout@v4

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          java-version: '21'
          distribution: 'temurin'
          cache: maven

      - name: Run Unit Tests
        run: ./mvnw test

      - name: Configure AWS Credentials via OIDC
        uses: aws-actions/configure-aws-credentials@v4
        with:
          role-to-assume: ${{ secrets.AWS_ROLE_ARN }}
          aws-region: us-east-1

      - name: Log in to Amazon ECR
        id: login-ecr
        uses: aws-actions/amazon-ecr-login@v2

      - name: Build and Push Docker Image
        env:
          ECR_REGISTRY: ${{ steps.login-ecr.outputs.registry }}
          ECR_REPOSITORY: file-transfer-service
          IMAGE_TAG: ${{ github.sha }}
        run: |
          docker build -t $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG -t $ECR_REGISTRY/$ECR_REPOSITORY:latest .
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:$IMAGE_TAG
          docker push $ECR_REGISTRY/$ECR_REPOSITORY:latest

      - name: Deploy to EC2
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.EC2_HOST }}
          username: ${{ secrets.EC2_USERNAME }}
          key: ${{ secrets.EC2_SSH_KEY }}
          script: |
            # 1. Log in to ECR on EC2
            aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin ${{ steps.login-ecr.outputs.registry }}

            # 2. Stop and remove existing container
            docker stop file-transfer-app || true
            docker rm file-transfer-app || true

            # 3. Pull latest image
            docker pull ${{ steps.login-ecr.outputs.registry }}/file-transfer-service:latest

            # 4. Run new container
            docker run -d \
              --name file-transfer-app \
              --restart always \
              -p 8080:8080 \
              ${{ steps.login-ecr.outputs.registry }}/file-transfer-service:latest

----
Phase 3: Push Code & Trigger Pipeline
Run the following commands on your local machine terminal:

# 1. Create a feature branch for your changes
git checkout -b feature/setup-cicd-pipeline

# 2. Stage and commit your files
git add .
git commit -m "Add Dockerfile and GitHub Actions workflow"

# 3. Switch back to main and merge (or open a PR on GitHub)
git checkout main
git merge feature/setup-cicd-pipeline

# 4. Push to main branch on GitHub to trigger deployment
git push origin main

Verification
Go to GitHub Repository -> Actions tab. You will see your workflow executing in real time through the stages: Test -> Build Container -> Push to ECR -> SSH Deploy.

Once complete, open your browser and navigate to:
http://<EC2_PUBLIC_IP>:8080/actuator/health or your API endpoint to confirm the application is live.