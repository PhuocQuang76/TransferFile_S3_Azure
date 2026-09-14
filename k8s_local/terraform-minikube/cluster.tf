terraform {
  required_version = ">= 1.3.0"
  required_providers {
    minikube = {
      source  = "scott-the-programmer/minikube"
      version = "~> 0.4.0"
    }
  }
}

resource "minikube_cluster" "node" {
  driver       = "docker"
  cluster_name = "minikube"
  addons = [
    "default-storageclass",
    "storage-provisioner"
  ]
}

output "cluster_endpoint" {
  value = minikube_cluster.node.host
}