package com.synergisticit.filetransfer.controler;

import com.synergisticit.filetransfer.model.TransferSummary;
import com.synergisticit.filetransfer.service.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Slf4j
@RestController
@RequestMapping("/api/v1/transfer")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * Triggers file migration from AWS S3 to Azure Blob Storage asynchronously.
     *
     * @param prefix    Optional folder path or prefix filter in S3 (e.g., "invoices/").
     * @param extension Optional file extension filter (e.g., ".pdf", ".csv").
     * @param overwrite If true, bypasses idempotency checks and forces re-upload of all files.
     * @return Mono<ResponseEntity<TransferSummary>> containing the non-blocking execution promise.
     */
    @PostMapping
    public Mono<ResponseEntity<TransferSummary>> triggerTransfer(
            @RequestParam(required = false) String prefix,
            @RequestParam(required = false) String extension,
            @RequestParam(defaultValue = "false") boolean overwrite) {

        log.info("Received request to trigger file transfer. prefix=[{}], extension=[{}], overwrite=[{}]",
                prefix, extension, overwrite);

        return transferService.executeTransfer(prefix, extension, overwrite)
                .map(summary -> {
                    if (summary.getTotalFailed() == 0) {
                        // Everything succeeded or skipped (200 OK)
                        return ResponseEntity.ok(summary);
                    } else if (summary.getTotalTransferred() > 0 || summary.getTotalSkipped() > 0) {
                        // Partial failure (207 Multi-Status)
                        return ResponseEntity.status(HttpStatus.MULTI_STATUS).body(summary);
                    } else {
                        // All operations failed (500 Internal Server Error)
                        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(summary);
                    }
                })
                .onErrorResume(e -> {
                    log.error("Critical error while executing transfer pipeline", e);
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build());
                });
    }
}