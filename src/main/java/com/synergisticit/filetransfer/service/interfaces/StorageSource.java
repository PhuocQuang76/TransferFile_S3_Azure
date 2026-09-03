package com.synergisticit.filetransfer.service.interfaces;

import com.synergisticit.filetransfer.model.StorageMetadata;
import com.synergisticit.filetransfer.model.StorageObject;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;

/**
 * Interface for source storage operations (S3, GCS, etc.).
 * This abstraction enables the Open/Closed Principle - new storage providers
 * can be added by implementing this interface without modifying existing code.
 * 
 * Implements Dependency Inversion Principle - high-level modules depend on this abstraction,
 * not concrete implementations.
 */
public interface StorageSource {
    
    /**
     * Lists objects in the storage with optional prefix and extension filtering.
     * 
     * @param prefix Optional subfolder/path filter
     * @param extension Optional file extension filter
     * @return Flux emitting matching storage objects
     */
    Flux<StorageObject> listObjects(String prefix, String extension);
    
    /**
     * Fetches metadata for a single object asynchronously.
     * Used for idempotency checks and metadata preservation.
     * 
     * @param path Object key/path in storage
     * @return Mono emitting storage metadata
     */
    Mono<StorageMetadata> getObjectMetadata(String path);
    
    /**
     * Provides a non-blocking byte stream for a single file.
     * Emits ByteBuffer chunks directly without buffering full file contents in RAM.
     * 
     * @param path Object key/path in storage
     * @return Flux emitting raw ByteBuffer chunks
     */
    Flux<ByteBuffer> getObjectStream(String path);
    
    /**
     * Deletes a file after successful transfer verification.
     * 
     * @param path Object key/path in storage
     * @return Mono signaling completion
     */
    Mono<Void> deleteObject(String path);
    
    /**
     * Gets the source identifier (bucket name, container name, etc.) for logging.
     * 
     * @return Source identifier
     */
    String getSourceIdentifier();
}
