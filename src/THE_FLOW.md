
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