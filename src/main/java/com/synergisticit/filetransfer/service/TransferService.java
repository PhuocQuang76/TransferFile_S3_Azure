package com.synergisticit.filetransfer.service;

import com.synergisticit.filetransfer.enums.TransferStatus;
import com.synergisticit.filetransfer.model.TransferResult;
import com.synergisticit.filetransfer.model.TransferSummary;
import com.synergisticit.filetransfer.service.interfaces.StorageDestination;
import com.synergisticit.filetransfer.service.interfaces.StorageSource;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
// @Service this bean already created in TransferConfig already
public class TransferService {

    private final StorageSource source;
    private final StorageDestination destination;
    private final int concurrencyLimit;
    private final boolean deleteAfterTransfer;

    /** A file that makes no progress within this window fails, freeing its concurrency slot. */
    private static final Duration FILE_TIMEOUT = Duration.ofMinutes(10);

    public TransferService(
            StorageSource source,             // Interface, not S3Service
            StorageDestination destination,   // Interface, not AzureBlobService
            int concurrencyLimit,
            boolean deleteAfterTransfer) {
        this.source = source;
        this.destination = destination;
        this.concurrencyLimit = concurrencyLimit;
        this.deleteAfterTransfer = deleteAfterTransfer;
    }

    /**
     * Orchestrates non-blocking file migration from source to destination storage.
     *
     * @param prefix    Optional subfolder path/prefix filter.
     * @param extension Optional file extension filter.
     * @param overwrite If true, bypasses idempotency checks and forces re-upload.
     * @return Mono<TransferSummary> containing execution metrics and per-file results.
     */
    public Mono<TransferSummary> executeTransfer(String prefix, String extension, boolean overwrite) {
        Instant startTime = Instant.now();
        String runId = UUID.randomUUID().toString();
        log.info("Starting transfer run [{}] (prefix={}, extension={}, overwrite={})", runId, prefix, extension, overwrite);

        return source.listObjects(prefix, extension)
                .flatMap(storageObject -> processSingleFile(storageObject.getKey(), storageObject.getSize(), overwrite),
                        concurrencyLimit)
                .collectList()
                .map(results -> buildSummary(results, startTime, runId));
    }

    /**
     * Processes a single file end-to-end: metadata lookup -> idempotency check -> stream upload -> optional deletion.
     *
     * @param fallbackSize Size to report if the metadata lookup itself fails; the real size comes from metadata.
     */
    private Mono<TransferResult> processSingleFile(String filePath, long fallbackSize, boolean overwrite) {
        Instant fileStartTime = Instant.now();

        return source.getObjectMetadata(filePath)
                .flatMap(metadata -> {
                    String contentType = metadata.getContentType();
                    long fileSize = metadata.getContentLength();
                    String sourceETag = metadata.getETag();

                    Mono<Boolean> shouldSkipMono = overwrite ?
                            Mono.just(false) :
                            destination.matchesSource(filePath, sourceETag, fileSize);

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
     */
    private int determineErrorCode(Throwable e) {
        String message = e.getMessage();
        if (e instanceof java.util.concurrent.TimeoutException) {
            return 504;
        }
        if (e instanceof java.io.FileNotFoundException || (message != null && message.contains("not found"))) {
            return 404;
        }
        if (e instanceof java.nio.file.AccessDeniedException || (message != null && message.contains("access denied"))) {
            return 403;
        }
        if (e instanceof IllegalArgumentException) {
            return 400;
        }
        if (e instanceof java.net.ConnectException || (message != null && message.contains("connection"))) {
            return 503;
        }
        return 500;
    }

    /**
     * Aggregates individual TransferResults into an overall TransferSummary report.
     */
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
