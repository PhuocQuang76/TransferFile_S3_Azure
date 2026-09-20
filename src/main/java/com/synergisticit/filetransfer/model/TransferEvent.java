package com.synergisticit.filetransfer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferEvent {
    private String transferId;
    private String fileName;
    private String sourceBucket;
    private String destinationContainer;
    private long   fileSize;           // ← from sizeInBytes
    private String status;             // ← "SUCCESS" / "FAILED"
    private String errorMessage;

    private int    statusCode;         // 200 on success, 403 AccessDeniedException/404 FileNotFoundException/500 ConnectException on failure
    private long   durationMs;         // ← from timeTakenMs
    private String timestamp;          // ← ISO-8601 string
}
