package com.synergisticit.filetransfer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
//just test
/**
 * Generic storage metadata representation that can represent S3 object metadata,
 * Azure blob properties, or GCS object metadata.
 * This abstraction enables the Open/Closed Principle by allowing different storage providers
 * to return a common metadata type.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageMetadata {
    private String contentType;
    private long contentLength;
    private String eTag;
    private long lastModified;
}


