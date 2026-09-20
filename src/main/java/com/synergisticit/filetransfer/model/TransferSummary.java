package com.synergisticit.filetransfer.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferSummary {

    private String runId;
    private int totalDiscovered;
    private int totalTransferred;
    private int totalSkipped;
    private int totalFailed;
    private long totalDurationMs;
    private List<TransferResult> results;
}