package com.synergisticit.filetransfer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic storage object representation that can represent S3 objects, Azure blobs, or GCS objects.
 * This abstraction enables the Open/Closed Principle by allowing different storage providers
 * to return a common object type.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StorageObject {
    private String key;
    private long size;
    private String eTag;
    private long lastModified;
}
