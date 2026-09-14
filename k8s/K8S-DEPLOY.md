# Terraform setup. The full flow is:

Build app
Build Docker image
Push to ECR
Create EKS cluster
Configure AWS access for EKS
Deploy app manifests to Kubernetes
Expose service through ALB or NLB
Keep DB and cloud secrets in AWS Secrets Manager or Kubernetes Secret

-------------------------
## Full infra you should have in Terraform
Your infrastructure should include:

VPC
public subnets
internet gateway
security groups
EC2 instance
RDS MySQL
ECR repository
S3 bucket
IAM roles
GitHub OIDC role
Secrets Manager secret
outputs for GitHub and app config


----
# Credentials

Kubernetes Secret for app runtime values that are cluster-local
AWS Secrets Manager for centralized cloud-managed secrets
or both, but in different roles
For your app specifically, the cleanest approach is:

keep AWS Secrets Manager as the source of truth for the app config
use Kubernetes Secret only if you want to inject values into the pod directly

---------


If you want, I can next help you do one of these:

verify all Terraform files are complete and correct
create the final AWS secret values for deployment
generate the exact GitHub Actions + EC2 deployment commands
help you apply the Terraform and test app startup on EC2


-------------------
7) Apply the files
Run:

kubectl apply -f k8s/

or one by one:
kubectl apply -f k8s/configmap.yaml
kubectl apply -f k8s/secret.yaml
kubectl apply -f k8s/deployment.yaml
kubectl apply -f k8s/service.yaml

# 1. kubectl apply -f k8s/configmap.yml
What it does: Creates a ConfigMap in your cluster.

Why it matters: It stores non-sensitive configuration data (like your database URL, environment names, or application properties) as key-value pairs so your Spring Boot container can read them at runtime without hardcoding them into your source code.

# 2. kubectl apply -f k8s/serviceaccount.yml
What it does: Creates a Kubernetes ServiceAccount.

Why it matters: This acts as the identity for your application pods. In AWS EKS, this file is typically bound to an AWS IAM Role via IRSA (IAM Roles for Service Accounts), allowing your Spring Boot app to securely access AWS services (like Secrets Manager or S3) without needing hardcoded credentials.

# 3. kubectl apply -f k8s/deployment.yml
What it does: Deploys your actual Spring Boot application container to the cluster.

Why it matters: This tells Kubernetes which Docker image to pull from your ECR repository, how many replicas (instances) to run, which ConfigMaps/ServiceAccounts to attach, and what CPU/memory limits or health checks to enforce.

# 4. kubectl apply -f k8s/service.yml
What it does: Creates an internal Kubernetes Service.

Why it matters: Pods are ephemeral—they get destroyed and recreated with new IP addresses frequently. A Service provides a stable internal networking endpoint and load-balances traffic across all running replicas of your Spring Boot app.

# 5. kubectl apply -f k8s/ingress.yml
What it does: Configures the Ingress resource (managed by the AWS Load Balancer Controller).

Why it matters: It provisions an external Application Load Balancer (ALB) in AWS, handles public traffic entering from the internet, maps domain names or paths (like /), and routes that traffic securely down to your internal Kubernetes Service.