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
     * Idempotency check - decides whether this exact source version is already present.
     *
     * Implementations compare the source identifier (eTag) that was stored alongside the object
     * during a previous transfer. That detects content changes, which a size comparison cannot:
     * an edited file of the same byte length has a different eTag and will be transferred again.
     *
     * Only a genuine "not found" may report false. Any other failure (403, 429, 5xx) must be
     * propagated as an error, so a transient problem is never mistaken for "absent" and does not
     * trigger a redundant upload.
     *
     * @param path         Destination file key/path
     * @param sourceETag   Source object's eTag, used as the content identity
     * @param expectedSize Source size in bytes, used as a fallback for objects transferred
     *                     before eTags were recorded
     * @return Mono emitting true if this exact version is already present, false if it must be transferred
     */
    Mono<Boolean> matchesSource(String path, String sourceETag, long expectedSize);

    /**
     * Streams raw byte buffers into destination storage asynchronously.
     * Configured with chunked parallel transfer options, metadata preservation, and retry logic.
     *
     * The source eTag is stored with the object so a later {@link #matchesSource} call can
     * recognise it. The returned identifier is the destination's own receipt for the write and
     * is the caller's proof that the upload completed - callers must not delete the source
     * without it.
     *
     * @param path          Destination path
     * @param byteStream    Reactive flux emitting ByteBuffer chunks from source
     * @param contentLength Total size in bytes
     * @param contentType   Content-Type header from source (e.g., "application/pdf")
     * @param sourceETag    Source object's eTag, recorded against the stored object
     * @return Mono emitting the destination eTag of the written object
     */
    Mono<String> uploadStream(String path, Flux<ByteBuffer> byteStream, long contentLength,
                              String contentType, String sourceETag);

    /**
     * Gets the destination identifier (container name, bucket name, etc.) for logging.
     *
     * @return Destination identifier
     */
    String getDestinationIdentifier();
}
