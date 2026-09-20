
# THE FLOW
                         HTTP
                          │
         ┌────────────────▼────────────────┐
         │      TransferController         │  POST /api/v1/transfer
         │  maps summary → 200 / 207 / 500 │  ?prefix= &extension= &overwrite=
         └────────────────┬────────────────┘
                          │ Mono<TransferSummary>
         ┌────────────────▼────────────────┐
         │        TransferService          │  ORCHESTRATION ONLY
         │  list → check → upload          │  knows nothing about AWS or Azure
         │       → delete → summarise      │  bounded concurrency (flatMap, n=5)
         └───────┬─────────────────┬───────┘
                 │                 │
     ┌───────────▼──────┐  ┌───────▼──────────────┐
     │  StorageSource   │  │ StorageDestination   │   ◄── PORTS
     │  (interface)     │  │ (interface)          │       domain language only
     └───────────┬──────┘  └───────┬──────────────┘
                 │                 │
     ┌───────────▼──────┐  ┌───────▼──────────────┐
     │    S3Service     │  │  AzureBlobService    │   ◄── ADAPTERS
     │  AWS SDK v2      │  │  Azure SDK           │       all SDK code lives here
     └───────────┬──────┘  └───────┬──────────────┘
                 │                 │
            ┌────▼────┐       ┌────▼──────┐
            │ AWS S3  │       │Azure Blob │
            └─────────┘       └───────────┘

# The rule: TransferService depends only on the two interfaces. No AWS or Azure type ever appears in it. That's what lets you mock both sides in tests and swap providers by configuration.

# 2. Runtime flow

POST /api/v1/transfer
│
├─ listObjects(prefix, extension)  ← ONE S3 call → Flux of N objects
│
└─ flatMap(concurrency = 5)        ← 5 files in flight, backpressure-bounded
   │
   per file:
   1. getObjectMetadata        → contentType
   2. idempotency check        → already there? → SKIPPED
   3. uploadStream             → S3 bytes stream straight into Azure
   4. deleteObject (optional)
   → TransferResult { status, errorCode, timeTakenMs }
   │
├─ collectList()
└─ buildSummary() → TransferSummary { discovered, transferred, skipped, failed, durationMs }


# Bytes flow as Flux<ByteBuffer> from the S3 async client directly into the Azure async client — never buffered to disk or heap, so file size is bounded by neither.

# 3. Configuration & wiring

application-{profile}.properties  +  environment variables (K8s Secret)
         │
         ▼
AppCredentialsConfig ────────► "secrets" Map bean
                                    │
┌───────────────┬───────────────────┼──────────────────┐
▼               ▼                   ▼                  ▼
AWSConfig      AzureConfig          S3Service       AzureBlobService
    │               │             (bucket name)     (container name)
S3AsyncClient   BlobServiceAsyncClient
(default cred                                    
provider chain)

          TransferConfig ──► selects source + destination by name
                             (storage.source / storage.destination)
                          ──► new TransferService(...)


# AWS credentials come from the SDK default chain — ~/.aws/credentials locally, env vars in Kubernetes, IAM role on EC2/EKS. No code change between environments.

# # 4. Deployment
   git push main
   │
   ▼  GitHub Actions
   ├─ ./mvnw package            (Java 17, Spring Boot 3.3.4)
   ├─ docker build              (multi-stage: JDK builder → JRE runtime)
   ├─ push image → ECR
   ├─ start minikube
   └─ helm upgrade --install
   │
   ▼
   ┌──────────────────────────────┐
   │ Deployment  (SPRING_PROFILES_ACTIVE=dev)
   │ Service     ClusterIP :8586
   │ HPA         1–5 pods @ 70% CPU
   │ Secret      AWS keys, Azure key
   └──────────────────────────────┘


# 5. Stack
   Concern	Choice
   Runtime	Java 17, Spring Boot 3.3.4, WebFlux (non-blocking)
   Async	Project Reactor — Mono / Flux
   Source	AWS SDK v2 S3AsyncClient
   Destination	Azure SDK BlobServiceAsyncClient
   Config	Spring properties + env vars (Secrets Manager removed)
   Persistence	MySQL (local/dev/prod), H2 (test) — currently unused, no entities
   Tests	JUnit 5, Mockito, StepVerifier, Testcontainers/LocalStack
   Deploy	Docker → ECR → Helm → minikube

# 6. The design decisions worth defending
   WebFlux over MVC — the workload is I/O-bound; thread-per-request would need thousands of threads to move thousands of files. Reactor holds none while waiting.
   Ports & Adapters — a new provider is a new class implementing an interface; nothing existing changes.
   Property-driven provider selection — swap S3→GCS by changing one config value.
   Per-file error isolation — one failure is a FAILED row, not an aborted job.
   Idempotent by design — re-runs skip what's already transferred, so a crashed job is simply re-run.


# -------------------------
# ERROR CODE
# 1. Who decides the codes

The HTTP standard defines what each number means (RFC 9110) — it's universal, not AWS or Azure specific. Both use them because their APIs are HTTP.

The server decides which one to send for a given request:

Code	Meaning	Who's at fault
200	OK	—
403 Forbidden	wrong credentials / no permission	you
404 Not Found	object doesn't exist	—
429 Too Many Requests	you're being throttled	you (too fast)
500 / 503	server error, try later	them


# -------------------------
STORAGE
      TransferService          ← knows only interfaces, no AWS / Azure / JPA
             │
   ┌─────────┼──────────────────┬──────────────────────┐
   ▼         ▼                  ▼                      ▼
StorageSource  StorageDestination  TransferResultStore    ◄── PORTS (interfaces)
   │                │                     │
S3Service      AzureBlobService   JpaTransferResultStore  ◄── ADAPTERS
   │                │                     │
AWS S3         Azure Blob              MySQL

------------
ERROR CODE

S3Exception         	AWS SDK 	S3 replied with an error — status inside
BlobStorageException	Azure SDK	Azure replied with an error — status inside
IllegalStateException	your code	your own validation failed (no eTag)
TimeoutException    	Reactor	    nothing happened within 10 minutes
ConnectException    	JDK sockets	couldn't reach the host at all — not credentials










------------------------ RUN MINIKUBE ----------------------
# 1. BUILD the image (doesn't run it) — compiles from source inside the container
docker build -t filetransfer:local .

# 2. COPY it into minikube's Docker daemon (separate from your Mac's)
minikube image load filetransfer:local

# 3. DEPLOY — Helm renders the chart into Kubernetes objects
#    (Deployment, Service, Secret, HPA) and Kubernetes runs them
helm upgrade --install filetransfer ./filetransfer-chart -f filetransfer-chart/values-minikube.yaml

# 4. VERIFY
kubectl get pods

# It creates a tunnel from your Mac into the pod.
# Run it and leave that terminal open
kubectl port-forward deployment/filetransfer-filetransfer 8586:8586

your Mac                          minikube cluster
localhost:8586  ──── tunnel ────►  pod :8586
                                   (filetransfer)

kubectl port-forward deployment/filetransfer-filetransfer 8586:8586
                     └──────────── target ────────────┘  └──┬──┘
                                                      local:pod port

# Why you need it
Your Service is ClusterIP:
service/filetransfer-filetransfer   ClusterIP   10.100.214.209   <none>   8586/TCP
ClusterIP means reachable only from inside the cluster. That 10.100.x.x address doesn't
exist on your Mac's network — curl can't reach it. Port-forward bridges the gap.


# Then open a second terminal and run:
curl -s http://localhost:8586/actuator/health
Expect {"status":"UP"}.

# Then the transfer:
curl -s -X POST "http://localhost:8586/api/v1/transfer?overwrite=true" | python3 -m json.tool


---------------------------------
# LOAD TESTING USING HEY# 
# Terminal 1 — keep port-forward running
kubectl port-forward deployment/filetransfer-filetransfer 8586:8586

# Terminal 2 — watch the HPA live
kubectl get hpa -w

kubectl get hpa  -w
                └── watch
It shows your HorizontalPodAutoscaler and then keeps the terminal open, printing a new line every time something changes — instead of printing once and exiting.

# Terminal 3 — generate load
hey -z 60s -c 50 http://localhost:8586/actuator/health
hey is a load-testing tool — it hammers a URL with many simultaneous requests so you can see how the app behaves under pressure.

hey  -z 60s  -c 50  http://localhost:8586/actuator/health
    └──┬──┘ └──┬─┘ └──────────────┬─────────────────────┘
    run for   50 concurrent      the URL to hit
    60 secs   workers

# What you should see
Within 15–30 seconds the HPA's TARGETS column climbs past 70%, then REPLICAS starts increasing:

NAME          TARGETS      MINPODS  MAXPODS  REPLICAS
...hpa        cpu: 4%/70%     1        5         1
...hpa        cpu: 180%/70%   1        5         1
...hpa        cpu: 180%/70%   1        5         3
...hpa        cpu: 95%/70%    1        5         5

# Confirm pod number
kubectl get pods will show new pods appearing.