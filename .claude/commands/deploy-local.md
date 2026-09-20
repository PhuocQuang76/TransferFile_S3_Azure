---
description: Build the image and deploy it to the local Minikube cluster via Helm
allowed-tools: Bash, Read, Edit
---

Deploy the current working tree to Minikube.

1. Sanity-check the chart renders before touching the cluster: `helm template filetransfer ./filetransfer-chart`. Fix any template errors first — plain Go template syntax only.
2. Build against the Minikube docker daemon so no registry push is needed:
   `eval $(minikube docker-env) && docker build -t filetransfer:local .`
3. `helm upgrade --install filetransfer ./filetransfer-chart --set image.repository=filetransfer --set image.tag=local`
4. `kubectl rollout status deployment/filetransfer-filetransfer --timeout=180s`
5. On failure, run `kubectl describe pod` and `kubectl logs deployment/filetransfer-filetransfer --tail=100` and diagnose — see the `deploy-debugger` agent's notes for the common causes.
6. On success, report the pod status and remind me of the port-forward command.

Ask before running anything that deletes cluster state or pushes an image to ECR.
