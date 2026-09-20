package com.synergisticit.filetransfer.adapter;

import static java.lang.Math.log;

import com.synergisticit.filetransfer.model.StorageMetadata;
import com.synergisticit.filetransfer.model.StorageObject;
import com.synergisticit.filetransfer.service.interfaces.StorageSource;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.*;

import java.nio.ByteBuffer;
import java.util.Map;

@Slf4j
@Service
public class S3Service implements StorageSource {

    private final S3AsyncClient s3AsyncClient;
    private final String bucketName;

    public S3Service(
            S3AsyncClient s3AsyncClient,
            @Qualifier("secrets") Map<String, String> secrets) {
        this.s3AsyncClient = s3AsyncClient;
        this.bucketName = secrets.get("s3_bucket_name");
    }

    /**
     * Non-blocking discovery of S3 objects with optional prefix and extension filtering.
     * Uses AWS S3 paginator publisher to iterate across pages asynchronously.
     *
     * @param prefix    Optional subfolder/path filter (e.g., "invoices/").
     * @param extension Optional file extension filter (e.g., ".pdf", ".csv").
     * @return Flux emitting matching StorageObject descriptors.
     */
    @Override
    public Flux<StorageObject> listObjects(String prefix, String extension) {
        log.info("Listing objects reactively from S3 bucket [{}] with prefix [{}] and extension [{}]",bucketName, prefix, extension);
        log.debug("S3 list parameters - bucket: {}, prefix: {}, extension: {}", bucketName, prefix, extension);


        //AWS SDK for Java v2 to create a request for listing objects inside an Amazon S3 bucket.
        ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                .bucket(bucketName);

        // Checks if a prefix (such as a folder path like "uploads/2026/") is provided.
        if (prefix != null && !prefix.isBlank()) {
            requestBuilder.prefix(prefix.trim());
        }

        return Flux.from(s3AsyncClient.listObjectsV2Paginator(requestBuilder.build()))
                .flatMapIterable(ListObjectsV2Response::contents) //Flattening Pages to Individual Objects
                .filter(s3Object -> {
                    if (extension == null || extension.isBlank()) {
                        return true;
                    }
                    return s3Object.key().toLowerCase().endsWith(extension.trim().toLowerCase());
                })
                //Transforms each AWS-native S3Object into your application's domain model (StorageObject
                .map(this::mapToStorageObject) //
                .doOnNext(obj -> log.debug("Discovered S3 object: key=[{}], size=[{}]",
                        obj.getKey(), obj.getSize()))
                .doOnError(e -> {
                    log.error("Error listing objects from bucket [{}]", bucketName, e);
                    log.warn("S3 listing failed for bucket [{}] - check credentials and permissions", bucketName);
                });
    }

    /**
     * Fetches metadata for a single S3 object asynchronously.
     * Used for idempotency checks and metadata preservation (e.g., Content-Type, Content-Length).
     *
     * @param path Object key inside S3.
     * @return Mono emitting StorageMetadata.
     */
    @Override
    public Mono<StorageMetadata> getObjectMetadata(String path) {
        log.debug("Fetching metadata for S3 object [{}] in bucket [{}]", path, bucketName);
        HeadObjectRequest request = HeadObjectRequest.builder()
                .bucket(bucketName)
                .key(path)
                .build();

        return Mono.fromFuture(() -> s3AsyncClient.headObject(request))
                .map(this::mapToStorageMetadata)
                .doOnSuccess(metadata -> log.debug("Successfully retrieved metadata for [{}]: content-type={}, size={}",
                        path, metadata.getContentType(), metadata.getContentLength()))
                .doOnError(e -> {
                    log.error("Failed to fetch metadata for S3 object [{}]", path, e);
                    log.warn("Metadata fetch failed for [{}] - object may not exist or access denied", path);
                });
    }

    /**
     * Non-blocking byte stream for a single file path in S3.
     * Emits ByteBuffer chunks directly from S3 without buffering full file contents in RAM.
     *
     * @param path Object key inside S3.
     * @return Flux emitting raw ByteBuffer chunks.
     */
    @Override
    public Flux<ByteBuffer> getObjectStream(String path) {
        log.debug("Opening reactive S3 input stream for key [{}] in bucket [{}]", path, bucketName);

        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(bucketName)
                .key(path)
                .build();

        return Mono.fromFuture(() -> s3AsyncClient.getObject(request, AsyncResponseTransformer.toPublisher()))
                .flatMapMany(Flux::from);
    }

    /**
     * Deletes a file from S3 after successful transfer verification (optional cleanup flag).
     *
     * @param path Object key inside S3.
     * @return Mono signaling completion.
     */
    @Override
    public Mono<Void> deleteObject(String path) {
        log.info("Deleting object [{}] from S3 bucket [{}]", path, bucketName);
        log.debug("Initiating S3 delete for key [{}] in bucket [{}]", path, bucketName);

        DeleteObjectRequest request = DeleteObjectRequest.builder()
                .bucket(bucketName)
                .key(path)
                .build();

        return Mono.fromFuture(() -> s3AsyncClient.deleteObject(request))
                .doOnSuccess(v -> log.debug("Successfully deleted [{}] from S3", path))
                .doOnError(e -> {
                    log.error("Failed to delete object [{}] from S3", path, e);
                    log.warn("Delete failed for [{}] - check permissions", path);
                })
                .then();
    }

    @Override
    public String getSourceIdentifier() {
        return bucketName;
    }

    private StorageObject mapToStorageObject(S3Object s3Object) {
        return StorageObject.builder()
                .key(s3Object.key())
                .size(s3Object.size())
                .eTag(s3Object.eTag())
                .lastModified(s3Object.lastModified().toEpochMilli())
                .build();
    }

    private StorageMetadata mapToStorageMetadata(HeadObjectResponse response) {
        return StorageMetadata.builder()
                .contentType(response.contentType())
                .contentLength(response.contentLength())
                .eTag(response.eTag())
                .lastModified(response.lastModified().toEpochMilli())
                .build();
    }
}