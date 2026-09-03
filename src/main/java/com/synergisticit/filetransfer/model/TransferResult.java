package com.synergisticit.filetransfer.model;

import com.synergisticit.filetransfer.enums.TransferStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferResult {

    private String fileName;
    private long sizeInBytes;
    private String sourceBucket;
    private String destinationContainer;
    private TransferStatus status;
    private int errorCode; // HTTP status codes: 200, 404, 500, etc.
    private String errorMessage;
    private long timeTakenMs;
    private Instant timestamp;
}