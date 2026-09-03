package com.synergisticit.filetransfer.service;

import com.synergisticit.filetransfer.enums.TransferStatus;
import com.synergisticit.filetransfer.model.StorageObject;
import com.synergisticit.filetransfer.model.TransferResult;
import com.synergisticit.filetransfer.model.TransferSummary;
import com.synergisticit.filetransfer.service.interfaces.StorageDestination;
import com.synergisticit.filetransfer.service.interfaces.StorageSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Slf4j
// @Service this bean already created in TransferConfig already
public class TransferService {

    private final StorageSource source;
    private final StorageDestination destination;
    private final int concurrencyLimit;
    private final boolean deleteAfterTransfer;
    


    public TransferService(
            StorageSource source, // Interface, not S3Service
            StorageDestination destination, // Interface, not AzureBlobService
            @Value("${transfer.concurrency:5}") int concurrencyLimit,
            @Value("${transfer.delete-after-transfer:false}") boolean deleteAfterTransfer) {
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

        return source.listObjects(prefix, extension)
                .flatMap(storageObject -> processSingleFile(storageObject.getKey(), storageObject.getSize(), overwrite), concurrencyLimit)
                .collectList()
                .map(results -> buildSummary(results, startTime));
    }

    /**
     * Processes a single file end-to-end: metadata lookup -> idempotency check -> stream upload -> optional deletion.
     */
    private Mono<TransferResult> processSingleFile(String filePath, long fileSize, boolean overwrite) {
        Instant fileStartTime = Instant.now();

        return source.getObjectMetadata(filePath)
                .flatMap(metadata -> {
                    String contentType = metadata.getContentType();

                    Mono<Boolean> shouldSkipMono = overwrite ?
                            Mono.just(false) :
                            destination.existsAndMatchesSize(filePath, fileSize);

                    return shouldSkipMono.flatMap(shouldSkip -> {
                        if (shouldSkip) {
                            log.debug("Skipping [{}] - file already exists in destination with matching size", filePath);
                            return Mono.just(TransferResult.builder()
                                    .fileName(filePath)
                                    .sizeInBytes(fileSize)
                                    .sourceBucket(source.getSourceIdentifier())
                                    .status(TransferStatus.SKIPPED)
                                    .errorCode(200)
                                    .timeTakenMs(Duration.between(fileStartTime, Instant.now()).toMillis())
                                    .timestamp(Instant.now())
                                    .build());
                        }

                        log.info("Transferring [{}] (size: {} bytes, type: {})", filePath, fileSize, contentType);
                        log.debug("Upload stream initiated for [{}]", filePath);

                        return destination.uploadStream(filePath, source.getObjectStream(filePath), fileSize, contentType)
                                .then(Mono.defer(() -> {
                                    if (deleteAfterTransfer) {
                                        return source.deleteObject(filePath);
                                    }
                                    return Mono.empty();
                                }))
                                .then(Mono.just(TransferResult.builder()
                                        .fileName(filePath)
                                        .sizeInBytes(fileSize)
                                        .sourceBucket(source.getSourceIdentifier())
                                        .status(TransferStatus.TRANSFERRED)
                                        .errorCode(200)
                                        .timeTakenMs(Duration.between(fileStartTime, Instant.now()).toMillis())
                                        .timestamp(Instant.now())
                                        .build()));
                    });
                })
                .onErrorResume(e -> {
                    log.error("Failed migration for file [{}]", filePath, e);
                    int errorCode = determineErrorCode(e);
                    log.warn("Error code [{}] for file [{}] - {}", errorCode, filePath, e.getMessage());
                    return Mono.just(TransferResult.builder()
                            .fileName(filePath)
                            .sizeInBytes(fileSize)
                            .sourceBucket(source.getSourceIdentifier())
                            .status(TransferStatus.FAILED)
                            .errorCode(errorCode)
                            .errorMessage(e.getMessage())
                            .timeTakenMs(Duration.between(fileStartTime, Instant.now()).toMillis())
                            .timestamp(Instant.now())
                            .build());
                });
    }

    /**
     * Determines HTTP error code based on exception type.
     */
    private int determineErrorCode(Throwable e) {
        String message = e.getMessage();
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
    private TransferSummary buildSummary(List<TransferResult> results, Instant startTime) {
        int totalDiscovered = results.size();
        int totalTransferred = (int) results.stream().filter(r -> r.getStatus() == TransferStatus.TRANSFERRED).count();
        int totalSkipped = (int) results.stream().filter(r -> r.getStatus() == TransferStatus.SKIPPED).count();
        int totalFailed = (int) results.stream().filter(r -> r.getStatus() == TransferStatus.FAILED).count();
        long totalDurationMs = Duration.between(startTime, Instant.now()).toMillis();

        log.info("Migration job finished: discovered={}, transferred={}, skipped={}, failed={}, duration={}ms",
                totalDiscovered, totalTransferred, totalSkipped, totalFailed, totalDurationMs);

        return TransferSummary.builder()
                .totalDiscovered(totalDiscovered)
                .totalTransferred(totalTransferred)
                .totalSkipped(totalSkipped)
                .totalFailed(totalFailed)
                .totalDurationMs(totalDurationMs)
                .results(results)
                .build();
    }
}