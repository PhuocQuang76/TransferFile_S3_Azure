# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Spring Boot 3.3.4 / Java 17 **reactive** (WebFlux + Project Reactor) service that streams files from AWS S3 to Azure Blob Storage without buffering them in memory. Credentials come from AWS Secrets Manager at startup. Deployed as a container to Minikube/EKS via a Helm chart.

`INTERVIEW.md` is the author's study notes on this codebase (SOLID mapping, Mono vs Flux, idempotency). It explains *why* the design looks the way it does — read it before proposing structural changes.

## Commands

```bash
./mvnw clean package                      # build + run tests
./mvnw clean package -DskipTests          # build only (what the Dockerfile and CI do)
./mvnw test                               # all tests
./mvnw test -Dtest=TransferServiceTest    # one test class
./mvnw test -Dtest=TransferServiceTest#testExecuteTransfer_Success   # one test method
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev                # run locally
```

There is no linter or formatter configured; Maven is the only build tool.

Trigger a transfer against a running instance (port `8586`):

```bash
curl -X POST "http://localhost:8586/api/v1/transfer?prefix=invoices/&extension=.pdf&overwrite=false"
curl http://localhost:8586/actuator/health
```

Local Kubernetes round-trip (see `k8s_local/README.md` for the full session log):

```bash
docker build -t filetransfer:local .
helm upgrade --install filetransfer ./filetransfer-chart --set image.repository=filetransfer --set image.tag=local
kubectl rollout status deployment/filetransfer-filetransfer
kubectl port-forward deployment/filetransfer-filetransfer 8586:8586
kubectl logs deployment/filetransfer-filetransfer -f
```

## Architecture

The transfer pipeline is built around two interfaces in `service/interfaces/`:

- `StorageSource` — `listObjects` / `getObjectMetadata` / `getObjectStream` / `deleteObject` (implemented by `S3Service`)
- `StorageDestination` — `existsAndMatchesSize` / `uploadStream` (implemented by `AzureBlobService`)

`TransferService` orchestrates: list → per-file metadata → idempotency check → stream upload → optional source delete → aggregate into a `TransferSummary`. It depends only on the interfaces, never on the SDK-backed services. Per-file failures are caught in `onErrorResume` and recorded as a `FAILED` `TransferResult`; the job never aborts. `TransferController` maps the summary to 200 (all good), 207 (partial), or 500 (everything failed).

### Wiring is property-driven, not annotation-driven

`TransferService` is **not** a `@Service`. It is constructed by hand in `TransferConfig`, which injects `Map<String, StorageSource>` and `Map<String, StorageDestination>` (all beans of each type, keyed by bean name) and picks one of each using the `storage.source` / `storage.destination` properties. Adding a provider means: implement the interface, annotate `@Service`, and point the property at the new bean name — no edit to `TransferService` or `TransferConfig`.

Byte streams flow as `Flux<ByteBuffer>` straight from the S3 async client into the Azure async client. Never `.block()`, never collect a whole file into a `byte[]`, and never call a blocking API inside the pipeline — that defeats the entire point of the design. (`TestInfrastructureConfig`'s test destination does buffer, deliberately, because it is a fake.)

### The `secrets` bean is the startup linchpin

`SecretsManagerConfig` exposes a single `Map<String, String>` bean named `secrets`, parsed from one AWS Secrets Manager JSON document (`s3-to-azure/credentials`). Its keys — `s3_bucket_name`, `azure_storage_account_name`, `azure_storage_account_key`, `azure_container_name`, `mysql_username`, `mysql_password` — are read by `S3Service`, `AzureConfig`, and `DataSourceConfig` via `@Qualifier("secrets")`.

Consequences worth knowing before debugging a startup failure:

- `AWSConfig` and `SecretsManagerConfig` are both `@Profile("!test")` **and** `@ConditionalOnProperty(aws.secrets.enabled=true)`. With `aws.secrets.enabled=false` (the default in `application.properties`) and no `test` profile, there is no `secrets` bean and no `S3AsyncClient`, so `S3Service` cannot be constructed and the context fails. Non-test profiles effectively require working AWS credentials and `aws.secrets.enabled=true`.
- Under the `test` profile, `TestInfrastructureConfig` supplies `@Primary` replacements for `secrets`, the `DataSource`, and the `S3AsyncClient` (LocalStack-pointed), plus a `testDestination` bean that writes to a second S3 bucket instead of Azure.
- `DataSourceConfig` is `@Primary` and `@Profile("!test")`, and always builds a Hikari pool with the **MySQL driver** from secrets/env, falling back to a hardcoded RDS URL. The H2 `spring.datasource.*` entries in `application.properties` / `application-dev.properties` are therefore ignored outside the `test` profile — changing them has no effect.

### Profiles

`local` is the default (`spring.profiles.default=local`). `local` targets a local MySQL, `dev`/`prod` target Secrets Manager plus RDS, `test` is fully self-contained. Helm sets `SPRING_PROFILES_ACTIVE` (`dev` in CI).

## Tests

- `src/test/java/.../unit/` — Mockito + `StepVerifier`, no Spring context. This is where transfer logic is actually covered.
- `src/test/java/.../integration/` — `AbstractCloudIntegrationTest` boots a LocalStack Testcontainer (S3 only) and rewrites properties via `@DynamicPropertySource`. `FileTransferIntegrationTest` is currently `@Disabled` and needs a running Docker daemon to re-enable.
- `FiletransferApplicationTests` is a `@ActiveProfiles("test")` context-load smoke test — it catches the bean-wiring breakage described above.

## Deployment layout

- `Dockerfile` — two-stage temurin 17 build, exposes 8586.
- `filetransfer-chart/` — Helm chart (Deployment, Service, HPA, Secret). `values.yaml` carries env + resource settings; AWS keys are injected as `--set secret.data.*` from GitHub secrets.
- `.github/workflows/minikube-deploy.yml` — the only active workflow: build jar → push image to ECR → start Minikube → `helm upgrade --install`. The `.bk` files beside it are disabled EKS/EC2 variants.
- `ec2/`, `k8s/`, `k8s_local/` — Terraform/Ansible/manifest experiments and runbooks. `terraform/` and `ansible/` at the repo root are gitignored.

## Conventions

- The controller package is spelled `controler` (single `l`). Match it; don't silently rename.
- Lombok `@Slf4j` + `@Builder` throughout; models are plain builders, not JPA entities despite `spring-boot-starter-data-jpa` being on the classpath.
- Logs go to `./logs` (and `./test-logs` for tests) via `logback.xml`; both directories are gitignored.
