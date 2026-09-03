package com.synergisticit.filetransfer.service;

import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.ParallelTransferOptions;
import com.synergisticit.filetransfer.service.interfaces.StorageDestination;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Map;

@Slf4j
@Service
public class AzureBlobService implements StorageDestination {

    //serves as the asynchronous, non-blocking handle for a specific Azure Blob Storage container (equivalent to an AWS S3 bucket).
    private final BlobContainerAsyncClient containerAsyncClient;
    private final String containerName;

    /**
     * Constructor that injects the pre-configured BlobServiceAsyncClient from AzureConfig.
     * Follows Dependency Injection principle - client creation is delegated to configuration.
     */
    public AzureBlobService(
            BlobServiceAsyncClient blobServiceAsyncClient,
            @Qualifier("secrets") Map<String, String> secrets) {
        this.containerName = secrets.get("azure_container_name");
        this.containerAsyncClient = blobServiceAsyncClient.getBlobContainerAsyncClient(containerName);
    }

    /**
     * Idempotency Check: Verifies asynchronously if a blob exists in Azure and matches the source S3 file size.
     *
     * @param blobName     Destination file key/path in Azure Blob Storage.
     * @param expectedSize Size in bytes of the file in S3.
     * @return Mono emitting true if the file exists and size matches, false otherwise.
     */
    @Override
    public Mono<Boolean> existsAndMatchesSize(String blobName, long expectedSize) {
        log.debug("Checking if blob [{}] exists in Azure container [{}] with expected size {}", blobName, containerName, expectedSize);
        var blobAsyncClient = containerAsyncClient.getBlobAsyncClient(blobName);

        return blobAsyncClient.exists()
                .flatMap(exists -> {
                    if (!exists) {
                        log.debug("Blob [{}] does not exist in Azure", blobName);
                        return Mono.just(false);
                    }
                    return blobAsyncClient.getProperties()
                            .map(properties -> {
                                boolean matches = properties.getBlobSize() == expectedSize;
                                log.debug("Blob [{}] exists, size check: expected={}, actual={}, matches={}",
                                        blobName, expectedSize, properties.getBlobSize(), matches);
                                return matches;
                            })
                            .onErrorResume(e -> {
                                log.warn("Error getting properties for blob [{}] - returning false", blobName);
                                return Mono.just(false);
                            });
                })
                .onErrorResume(e -> {
                    log.warn("Error checking existence of blob [{}] - returning false", blobName);
                    return Mono.just(false);
                });
    }

    /**
     * Streams raw byte buffers directly into Azure Blob Storage asynchronously without loading the file into heap RAM.
     * Configured with chunked parallel transfer options, metadata preservation, and retry logic with backoff.
     *
     * @param blobName      Destination path in Azure.
     * @param byteStream    Reactive flux emitting ByteBuffer chunks from S3.
     * @param contentLength Total size in bytes.
     * @param contentType   Content-Type header from S3 (e.g. "application/pdf").
     * @return Mono signaling upload completion.
     */
    @Override
    public Mono<Void> uploadStream(String blobName, Flux<ByteBuffer> byteStream, long contentLength, String contentType) {
    log.debug("Starting upload to Azure blob [{}] in container [{}], size: {}, content-type: {}",
            blobName, containerName, contentLength, contentType);
    // 1. Spawns an asynchronous client for this specific target file path
    var blobAsyncClient = containerAsyncClient.getBlobAsyncClient(blobName);

    // 2. Configures chunking: Streams in 4MB blocks with up to 2 parallel block uploads
    ParallelTransferOptions transferOptions = new ParallelTransferOptions()
            .setBlockSizeLong(4L * 1024 * 1024)
            .setMaxConcurrency(2);
    log.debug("Azure transfer options configured - block size: 4MB, max concurrency: 2");

    // 3. Preserves the MIME type (e.g. text/plain, application/pdf) from S3
    BlobHttpHeaders headers = new BlobHttpHeaders();
    if (contentType != null && !contentType.isBlank()) {
        headers.setContentType(contentType);
    }

    // 4. Connects the S3 byte stream directly to Azure's network socket
    return blobAsyncClient.uploadWithResponse(byteStream, transferOptions, headers, null, null, null)
            
            // 5. If network hiccups happen, automatically retries up to 3 times with 2s exponential backoff
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                    .doBeforeRetry(signal -> log.warn("Retrying upload for [{}] (Attempt {}/3) due to: {}",
                            blobName, signal.totalRetries() + 1, signal.failure().getMessage())))
            
            // 6. Drops the response object and returns Mono<Void> (signaling completion)
            .then()
            
            // 7. Logs success when Azure finishes receiving all blocks
            .doOnSuccess(v -> log.info("Successfully uploaded [{}] to Azure Blob Storage", blobName));
    }

    @Override
    public String getDestinationIdentifier() {
        return containerName;
    }
}