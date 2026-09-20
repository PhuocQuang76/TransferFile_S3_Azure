package com.synergisticit.filetransfer.service;

import com.synergisticit.filetransfer.enums.TransferStatus;
import com.synergisticit.filetransfer.model.TransferResult;
import com.synergisticit.filetransfer.model.TransferSummary;
import com.synergisticit.filetransfer.service.interfaces.StorageDestination;
import com.synergisticit.filetransfer.service.interfaces.StorageSource;
import com.synergisticit.filetransfer.service.interfaces.TransferEventPublisher;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.s3.model.S3Exception;

import com.azure.storage.blob.models.BlobStorageException;
import java.net.ConnectException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;


@Slf4j
// @Service this bean already created in TransferConfig already
public class TransferService {

    private final StorageSource source;
    private final StorageDestination destination;
    private final TransferEventPublisher tranferEventPublisher;
    private final int concurrencyLimit;
    private final boolean deleteAfterTransfer;

    //10-minute safety threshold. If a single file upload freezes or makes no progress
    //within 10 minutes, the reactive stream cuts it off to free up resources.
    private static final Duration FILE_TIMEOUT = Duration.ofMinutes(10);

    public TransferService(
            StorageSource source,                    // Interface, not S3Service
            StorageDestination destination,          // Interface, not AzureBlobService
            TransferEventPublisher tranferEventPublisher,   // Interface, not the Event Hubs SDK
            int concurrencyLimit,
            boolean deleteAfterTransfer) {
        this.source = source;
        this.destination = destination;
        this.tranferEventPublisher = tranferEventPublisher;
        this.concurrencyLimit = concurrencyLimit;
        this.deleteAfterTransfer = deleteAfterTransfer;
    }

    /**
     * Orchestrates non-blocking file migration from source to destination storage.
     * @param prefix    Optional subfolder path/prefix filter.
     * @param extension Optional file extension filter.
     * @param overwrite If true, bypasses idempotency checks and forces re-upload.
     * @return Mono<TransferSummary> containing execution metrics and per-file results.
     */
    public Mono<TransferSummary> executeTransfer(String prefix, String extension, boolean overwrite) {
        Instant startTime = Instant.now();  //timestamp when the overall transfer batch starts
        String runId = UUID.randomUUID().toString();  //Generates a unique tracking ID
        log.info("Starting transfer run [{}] (prefix={}, extension={}, overwrite={})", runId, prefix, extension, overwrite);

        //Calls the storage source to fetch a reactive stream (Flux) of file objects matching the given prefix and extension.
        return source.listObjects(prefix, extension)
                //Takes each file from the stream and calls processSingleFile to handle its streaming transfer from S3 to Azure.

                //This processSingleFile return a result. where "FAILED" or "TRANSFERRED"" or "SKIPPED""
                .flatMap(storageObject -> processSingleFile(storageObject.getKey(), storageObject.getSize(), overwrite)

                                .flatMap(result -> tranferEventPublisher.publish(result).thenReturn(result)),
                                // Individual file finishes transferring, this takes its result, publishes a tracking event
                                // to Azure Event Hub, and then passes (thenReturn) the original result down the chain.
                        concurrencyLimit)
                //concurrencyLimitr of flatMap restricts how many files are allowed to transfer concurrently
                // at the exact same time (preventing Out Of Memory errors and API throttling).


                .collectList()
                .map(results -> buildSummary(results, startTime, runId));
    }

    /**
     * Processes a single file end-to-end: metadata lookup -> idempotency check -> stream upload -> optional deletion.
     * @param fallbackSize Size to report if the metadata lookup itself fails; the real size comes from metadata.
     */
    private Mono<TransferResult> processSingleFile(String filePath, long fallbackSize, boolean overwrite) {
        Instant fileStartTime = Instant.now();


        return source.getObjectMetadata(filePath)
        //fetches the source file metadata (size, content type, ETag). If overwrite is false.
        //it asks the destination if the file already exists with matching attributes (matchesSource).

                .flatMap(metadata -> {
                    String contentType = metadata.getContentType();
                    long fileSize = metadata.getContentLength();
                    String sourceETag = metadata.getETag();

                    //Skip check idempotency if overwrite is set == true
                    //If the destination already holds it, it skips the network upload, records a SKIPPED status, and exits early.
                    Mono<Boolean> shouldSkipMono = overwrite ?
                            Mono.just(false) :

                            destination.matchesSource(filePath, sourceETag, fileSize);
                            //check and comapre with azure existing file name

                    return shouldSkipMono.flatMap(shouldSkip -> {
                        if (shouldSkip) {
                            log.debug("Skipping [{}] - destination already holds this source version", filePath);
                            return Mono.just(result(filePath, fileSize, TransferStatus.SKIPPED, 200, null, fileStartTime));
                        }

                        log.info("Transferring [{}] (size: {} bytes, type: {})", filePath, fileSize, contentType);

                        return destination.uploadStream(filePath, source.getObjectStream(filePath), fileSize,
                                        contentType, sourceETag)
                                .flatMap(destinationETag -> {
                                    // The destination eTag is proof the write completed. Without it we must
                                    // not delete the source - that is how files get lost.
                                    if (destinationETag == null || destinationETag.isBlank()) {
                                        return Mono.error(new IllegalStateException(
                                                "Upload of [" + filePath + "] returned no eTag - source was NOT deleted"));
                                    }

                                    //If it safe to delete , then delete
                                    if (deleteAfterTransfer) {
                                        log.debug("Upload of [{}] confirmed (eTag {}), deleting source", filePath, destinationETag);
                                        return source.deleteObject(filePath);
                                    }
                                    return Mono.empty();
                                })
                                .then(Mono.just(result(filePath, fileSize, TransferStatus.TRANSFERRED, 200, null, fileStartTime)));
                    });
                })
                .timeout(FILE_TIMEOUT)
                .onErrorResume(e -> {
                    log.error("Failed migration for file [{}]", filePath, e);
                    int errorCode = determineErrorCode(e);
                    log.warn("Error code [{}] for file [{}] - {}", errorCode, filePath, e.getMessage());
                    return Mono.just(result(filePath, fallbackSize, TransferStatus.FAILED, errorCode,
                            e.getMessage(), fileStartTime));
                });
    }

    /** Builds one per-file outcome record. */
    private TransferResult result(String filePath, long fileSize, TransferStatus status, int errorCode,
                                  String errorMessage, Instant fileStartTime) {
        return TransferResult.builder()
                .fileName(filePath)
                .sizeInBytes(fileSize)
                .sourceBucket(source.getSourceIdentifier())
                .destinationContainer(destination.getDestinationIdentifier())
                .status(status)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .timeTakenMs(Duration.between(fileStartTime, Instant.now()).toMillis())
                .timestamp(Instant.now())
                .build();
    }

    /**
     * Determines HTTP error code based on exception type.
     * Maps a failure to an HTTP-style code for the TransferResult.
     * Reads the status the service actually returned rather than guessing from exception
     * types - the SDKs already captured it from the HTTP response.
     */
    private int determineErrorCode(Throwable e) {
        Throwable cause = unwrapCause(e);

        if (cause instanceof TimeoutException) {
            return 504;                     // our own .timeout() fired
        }
        if (cause instanceof S3Exception s3) {
            return s3.statusCode();         // whatever S3 replied: 403, 404, 500...
        }
        if (cause instanceof BlobStorageException azure) {
            return azure.getStatusCode();   // whatever Azure replied
        }
        if (cause instanceof IllegalStateException) {
            return 422;                     // our own check, e.g. upload returned no eTag
        }
        if (cause instanceof java.io.FileNotFoundException) {
            return 404;                     // not thrown by the SDKs, but semantically right
        }
        if (cause instanceof java.nio.file.AccessDeniedException) {
            return 403;
        }
        if (cause instanceof IllegalArgumentException) {
            return 400;
        }
        if (cause instanceof ConnectException) {
            return 503;                     // could not reach the host at all
        }
        return 500;                         // genuinely unknown
    }

    /** SDK futures wrap the real cause; unwrap so the instanceof checks above can see it. */
    private static Throwable unwrapCause(Throwable e) {
        Throwable current = e;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private TransferSummary buildSummary(List<TransferResult> results, Instant startTime, String runId) {
        int totalDiscovered = results.size();
        int totalTransferred = (int) results.stream().filter(r -> r.getStatus() == TransferStatus.TRANSFERRED).count();
        int totalSkipped = (int) results.stream().filter(r -> r.getStatus() == TransferStatus.SKIPPED).count();
        int totalFailed = (int) results.stream().filter(r -> r.getStatus() == TransferStatus.FAILED).count();
        long totalDurationMs = Duration.between(startTime, Instant.now()).toMillis();

        log.info("Run [{}] finished: discovered={}, transferred={}, skipped={}, failed={}, duration={}ms",
                runId, totalDiscovered, totalTransferred, totalSkipped, totalFailed, totalDurationMs);

        return TransferSummary.builder()
                .runId(runId)
                .totalDiscovered(totalDiscovered)
                .totalTransferred(totalTransferred)
                .totalSkipped(totalSkipped)
                .totalFailed(totalFailed)
                .totalDurationMs(totalDurationMs)
                .results(results)
                .build();
    }
}


/*
Here is how it works step-by-step:
Checks Destination Existence: It looks up the file at filePath in your Azure container to see if it even exists. If it doesn't exist, it returns false (meaning: we must transfer it).
Compares the ETag (Fingerprint): If the file does exist in Azure, it compares the destination blob's ETag with the sourceETag you passed in. If the ETags match, it proves the file contents are identical.
Compares the File Size: It verifies that the fileSize matches as well to ensure no partial or corrupted uploads were left behind.
The Decision: If both the ETag and file size match, it returns true. This tells your processSingleFile logic: "Stop, the exact same file is already in Azure, so we can skip the upload entirely and mark it as SKIPPED."
This prevents your application from wasting bandwidth re-uploading files that haven't changed since the last run.
 */