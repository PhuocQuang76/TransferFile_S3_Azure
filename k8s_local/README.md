# terraform-minikube

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
