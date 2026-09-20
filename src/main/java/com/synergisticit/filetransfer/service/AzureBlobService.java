package com.synergisticit.filetransfer.adapter;

import com.azure.storage.blob.BlobContainerAsyncClient;
import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobStorageException;
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
import java.io.IOException;
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
    public Mono<Boolean> matchesSource(String blobName, String sourceETag, long expectedSize) {
        log.debug("Checking blob [{}] in container [{}] against source eTag [{}]", blobName, containerName, sourceETag);

        // ONE call: getProperties returns 404 when the blob is absent, so a separate exists() is redundant.
        return containerAsyncClient.getBlobAsyncClient(blobName)
                .getProperties()
                .map(properties -> {
                    String storedETag = properties.getMetadata() == null
                            ? null
                            : properties.getMetadata().get(SOURCE_ETAG_KEY);

                    if (storedETag == null) {
                        // Transferred before eTags were recorded - fall back to the old size comparison
                        // so existing blobs are not needlessly re-uploaded.
                        boolean sizeMatches = properties.getBlobSize() == expectedSize;
                        log.debug("Blob [{}] has no source eTag, falling back to size: expected={}, actual={}, matches={}",
                                blobName, expectedSize, properties.getBlobSize(), sizeMatches);
                        return sizeMatches;
                    }

                    boolean matches = normalise(sourceETag).equals(normalise(storedETag));
                    log.debug("Blob [{}] eTag check: source={}, stored={}, matches={}",
                            blobName, sourceETag, storedETag, matches);
                    return matches;
                })
                .onErrorResume(BlobStorageException.class, e -> {
                    if (e.getStatusCode() == 404) {
                        log.debug("Blob [{}] does not exist in Azure - will transfer", blobName);
                        return Mono.just(false);
                    }
                    // 403 / 429 / 5xx are real failures. Reporting them as "absent" would trigger a
                    // pointless re-upload and, under throttling, make the problem worse.
                    log.error("Cannot verify blob [{}] - Azure returned {}", blobName, e.getStatusCode());
                    return Mono.error(e);
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
    public Mono<String> uploadStream(String blobName, Flux<ByteBuffer> byteStream, long contentLength,
                                     String contentType, String sourceETag) {
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
    // 3b. Record the source eTag so the next run can tell whether the source has changed
    Map<String, String> metadata = Map.of(SOURCE_ETAG_KEY, normalise(sourceETag));

    // 4. Connects the S3 byte stream directly to Azure's network socket
    return blobAsyncClient.uploadWithResponse(byteStream, transferOptions, headers, metadata, null, null)
            
            // 5. If network hiccups happen, automatically retries up to 3 times with 2s exponential backoff
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(2))
                    .filter(AzureBlobService::isRetryable)
                    .doBeforeRetry(signal -> log.warn("Retrying upload for [{}] (Attempt {}/3) due to: {}",
                            blobName, signal.totalRetries() + 1, signal.failure().getMessage())))
            
            // 6. Keeps Azure's eTag as the caller's proof that the write completed
            .map(response -> response.getValue().getETag())
            
            // 7. Logs success when Azure finishes receiving all blocks
            .doOnSuccess(eTag -> log.info("Successfully uploaded [{}] to Azure Blob Storage (eTag {})", blobName, eTag));
    }

    /** Azure metadata key holding the source object's eTag. Must be a valid C# identifier. */
    private static final String SOURCE_ETAG_KEY = "source_etag";

    /** S3 returns eTags wrapped in literal quotes; strip them so comparisons line up. */
    private static String normalise(String eTag) {
        return eTag == null ? "" : eTag.replace("\"", "");
    }

    /** Only transient failures are worth retrying - a 403 or 404 will never succeed. */
    private static boolean isRetryable(Throwable e) {
        if (e instanceof BlobStorageException azureException) {
            int code = azureException.getStatusCode();
            return code == 429 || code >= 500;
        }
        return e instanceof IOException;
    }

    @Override
    public String getDestinationIdentifier() {
        return containerName;
    }
}