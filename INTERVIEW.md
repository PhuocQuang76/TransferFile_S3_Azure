

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
