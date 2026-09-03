package com.synergisticit.filetransfer.service.interfaces;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;

/**
 * Interface for destination storage operations (Azure Blob, GCS, etc.).
 * This abstraction enables the Open/Closed Principle - new storage providers
 * can be added by implementing this interface without modifying existing code.
 * 
 * Implements Dependency Inversion Principle - high-level modules depend on this abstraction,
 * not concrete implementations.
 */
public interface StorageDestination {
    
    /**
     * Idempotency check - verifies if a blob exists and matches the expected size.
     * 
     * @param path Destination file key/path
     * @param expectedSize Expected size in bytes from source
     * @return Mono emitting true if file exists and size matches, false otherwise
     */
    Mono<Boolean> existsAndMatchesSize(String path, long expectedSize);
    
    /**
     * Streams raw byte buffers into destination storage asynchronously.
     * Configured with chunked parallel transfer options, metadata preservation, and retry logic.
     * 
     * @param path Destination path
     * @param byteStream Reactive flux emitting ByteBuffer chunks from source
     * @param contentLength Total size in bytes
     * @param contentType Content-Type header from source (e.g., "application/pdf")
     * @return Mono signaling upload completion
     */
    Mono<Void> uploadStream(String path, Flux<ByteBuffer> byteStream, long contentLength, String contentType);
    
    /**
     * Gets the destination identifier (container name, bucket name, etc.) for logging.
     * 
     * @return Destination identifier
     */
    String getDestinationIdentifier();
}
