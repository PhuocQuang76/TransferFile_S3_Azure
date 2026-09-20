# terraform-minikube
minikube start --driver=docker --addons=default-storageclass,storage-provisioner

What's different
                       minikube                         	EKS
Where it runs	       one Docker container on your Mac	     AWS-managed control plane + EC2/Fargate nodes
Nodes	               1 (control plane and worker in one)	many, spread across availability zones
Cost                   $0                               	~$73/mo control plane + node costs
Creation time          ~1 minute                        	~15–20 minutes
Survives	           dies when your laptop sleeps	managed, multi-AZ
LoadBalancer service   faked via minikube tunnel        	provisions a real ALB/NLB
Storage	your           local disk	                        EBS / EFS
IAM integration        none                             	IRSA / Pod Identity


---->>>>
# How to check 
Is the cluster up?
    minikube status

What's in it?
    kubectl get all

-----------------------------------------------
# terraform-aws

# Helm Chart
    - Brew install heml
    - create helm chart
        values.yaml file will 


### Apply YML Manifest
kubectl apply -f secret.yml
kubectl apply -f deployment.yml
kubectl apply -f service.yml


# Check if your application containers are running:
kubectl get pods

# Github secrets
AWS_ACCESS_KEY_ID
AWS_SECRET_ACCESS_KEY
AWS_REGION (us-east-1)
AWS_ACCOUNT_ID (011243863866)
AWS_SECRETS_SECRET_NAME (s3-to-azure/credentials)


# Minikube values:
AWS_REGION: us-east-1
AWS_ACCOUNT_ID: your AWS account id
SPRING_DATASOURCE_URL: jdbc:mysql://mysql:3306/filetransfer?createDatabaseIfNotExist=true&useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true
SPRING_DATASOURCE_USERNAME: root
SPRING_DATASOURCE_PASSWORD: root
AWS_SECRETS_SECRET_NAME: s3-to-azure/credentials




# Using minkube

# Applied Helm overrides to switch to H2 database, disable external MySQL/RDS, and configure environment variables:

  helm upgrade --install filetransfer ./filetransfer-chart \
  --set image.repository="filetransfer" \
  --set image.tag="local" \
  --set env.SPRING_PROFILES_ACTIVE="dev" \
  --set env.AWS_SECRETS_ENABLED="false" \
  --set secret.data.AWS_ACCESS_KEY_ID="" \
  --set secret.data.AWS_SECRET_ACCESS_KEY="" \
  --set env.AWS_REGION="us-east-1" \
  --set env.SPRING_DATASOURCE_URL="jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1" \
  --set env.SPRING_DATASOURCE_DRIVER_CLASS_NAME="org.h2.Driver" \
  --set env.SPRING_DATASOURCE_USERNAME="sa" \
  --set env.SPRING_DATASOURCE_PASSWORD=""

# Restarted the Kubernetes deployment and tailed the logs to verify startup:
kubectl rollout restart deployment/filetransfer-filetransfer

# get log 
kubectl logs deployment/filetransfer-filetransfer -f

## Networking & Port Forwarding
# Established port-forwarding to access the app on port 8586 locally:
kubectl port-forward deployment/filetransfer-filetransfer 8586:8586

# Health Checks & Actuator Testing
curl http://localhost:8586/actuator/health

# Configured Helm to expose detailed health components:
elm upgrade --install filetransfer ./filetransfer-chart \
  --set image.repository="filetransfer" \
  --set image.tag="local" \
  --set env.MANAGEMENT_ENDPOINT_HEALTH_SHOW_DETAILS="always"


# Checked specific liveness status:
curl http://localhost:8586/actuator/health/liveness

# Created a local test file and uploaded it to your AWS S3 bucket:

echo "Hello from Aileen's S3-to-Azure transfer test!" > test14.txt
aws s3 cp test14.txt s3://aileen-bucket-011243863866/test14.txt

# Triggered the file transfer REST API endpoint via POST:

curl -X POST "http://localhost:8586/api/v1/transfer?overwrite=true"

# How to run more pods (Scaling)
kubectl scale deployment filetransfer-filetransfer --replicas=3

# Using Helm during an upgrade:
helm upgrade filetransfer ./filetransfer-chart --set replicaCount=3

# Check that all 3 pods are running
kubectl get pods -w

# How to Enable Auto-Scaling
kubectl autoscale deployment filetransfer-filetransfer --cpu-percent=70 --min=1 --max=5

### LOAD TESTIN USING HEY  ###
brew install hey

### Step-by-Step to Set Up and Test HPA
# Run this command to tell Kubernetes to target 50% CPU usage, keeping between 1 and 5 replicas:

kubectl autoscale deployment filetransfer-filetransfer --cpu-percent=50 --min=1 --max=5

# 2. Check if the HPA is active
kubectl get hpa
-> 
aileen@aileens-mbp filetransfer % kubectl get hpa

NAME                            REFERENCE                              TARGETS              MINPODS   MAXPODS   REPLICAS   AGE

filetransfer-filetransfer-hpa   Deployment/filetransfer-filetransfer   cpu: <unknown>/70%   1         5         3          91m

aileen@aileens-mbp filetransfer %  

# need to add / Enable Metrics Server in Minikube
Metrics Server Required
The cpu: <unknown>/70% output means Kubernetes cannot read your pods' CPU usage yet because Minikube's metrics-server addon is not enabled. The HPA needs metrics-server to see when to scale.

minikube addons enable metrics-server

-> 
aileen@aileens-mbp filetransfer % kubectl get hpa                      
NAME                            REFERENCE                              TARGETS              MINPODS   MAXPODS   REPLICAS   AGE
filetransfer-filetransfer-hpa   Deployment/filetransfer-filetransfer   cpu: <unknown>/70%   1         5         3          93m
aileen@aileens-mbp filetransfer % 

# 1. Verify if metrics-server can read pod CPU

kubectl top pods



