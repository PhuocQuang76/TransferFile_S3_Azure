package com.synergisticit.filetransfer.service;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventHubProducerAsyncClient;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.synergisticit.filetransfer.enums.TransferStatus;
import com.synergisticit.filetransfer.model.TransferEvent;
import com.synergisticit.filetransfer.model.TransferResult;
import com.synergisticit.filetransfer.service.interfaces.TransferEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Publishes one JSON event per transferred file to Azure Event Hubs.
 *
 * Gated on the same property as EventHubConfig, which supplies the producer client. The two
 * conditions must agree: @ConditionalOnBean is unreliable on a component-scanned @Service because
 * scan order is undefined, so both are keyed on azure.eventhub.enabled instead. When Event Hubs
 * is off, NoOpTransferEventPublisher takes over so TransferService always has something to call.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "azure.eventhub.enabled", havingValue = "true")
/* Registers this class as a Spring service bean, gated by the exact same feature flag as the
configuration. (If Event Hubs is turned off, a fallback "NoOp" publisher takes over instead). */

// This class to serialize file transfer results into JSON and send them to Azure Event Hubs reactively.
public class EventHubTransferEventPublisher implements TransferEventPublisher {
    private final EventHubProducerAsyncClient producer;
    private final ObjectMapper objectMapper;

    public EventHubTransferEventPublisher(EventHubProducerAsyncClient producer, ObjectMapper objectMapper) {
        this.producer = producer;
        this.objectMapper = objectMapper;
    }

    @Override
    //Mono<Void> — a promise of "done," carrying no value. The caller only needs to know when the publish finished, not what it produced.
    public Mono<Void> publish(TransferResult result) {
        // If a file was skipped because it already existed in Azure,
        // it bypasses event publishing so you don't inflate your daily transfer metrics..
        if (result.getStatus() == TransferStatus.SKIPPED) {
            return Mono.empty();
        }

        // Uses Jackson (objectMapper) to serialize your Java TransferEvent object into a JSON string..
        // fromCallable safely wraps this synchronous CPU-bound operation into a reactive Mono
        return Mono.fromCallable(() -> objectMapper.writeValueAsString(toEvent(result)))
                // Keying on the file name puts every event about one file in the same partition,
                // so a consumer sees that file's history in order (e.g. FAILED then SUCCESS on a retry).

                .flatMap(json -> producer
                //Each flatMap says: "when this async thing finishes, use its value to start the next async thing, and give me one flat result."
                        .createBatch(new CreateBatchOptions().setPartitionKey(result.getFileName()))
                        .flatMap(batch -> {
                            // tryAdd is the ONLY signal that the event made it in - EventDataBatch
                            // exposes no way to read its contents back. Discarding the boolean would
                            // send an empty batch that Azure accepts, losing the event silently.
                            if (!batch.tryAdd(new EventData(json))) {
                                return Mono.error(new IllegalStateException(
                                        "Transfer event for [" + result.getFileName()
                                                + "] exceeds the maximum batch size of "
                                                + batch.getMaxSizeInBytes() + " bytes"));
                            }
                            return producer.send(batch);
                        }))
                        /*
                        Assigns the file name as the hash key.
                        this forces all events belonging to the same file to land in the exact same partition,
                        guaranteeing chronological order (e.g., a failure event followed by a retry success).
                         */
                .doOnSuccess(v -> log.info("Published transfer event for [{}] (status {})",
                        result.getFileName(), result.getStatus()))
                //A side-effect operator that triggers only when the network send operation completes successfully.
                //prints a clean confirmation log containing the file name and its final transfer status (SUCCESS or FAILED)

                .onErrorResume(e -> {
                    // The transfer itself already succeeded or failed on its own merits. Losing the
                    // announcement must not change that outcome.
                    log.error("Failed to publish transfer event for [{}] - transfer outcome is unaffected",
                            result.getFileName(), e);
                    return Mono.empty();
                });
                //any network exceptions or Azure timeouts if Event Hubs goes down.
                //Instead of letting the error crash the file transfer stream, it logs the error and returns Mono.empty()
    }

    /** Maps the internal result onto the published contract. Field names differ deliberately. */
    private TransferEvent toEvent(TransferResult r) {
        return TransferEvent.builder()
                .transferId(UUID.randomUUID().toString())
                .fileName(r.getFileName())
                .sourceBucket(r.getSourceBucket())
                .destinationContainer(r.getDestinationContainer())
                .fileSize(r.getSizeInBytes())
                .status(r.getStatus() == TransferStatus.FAILED ? "FAILED" : "SUCCESS")
                .errorMessage(r.getErrorMessage())
                .statusCode(r.getErrorCode())
                .durationMs(r.getTimeTakenMs())
                .timestamp(r.getTimestamp() == null ? null : r.getTimestamp().toString())
                .build();
    }
}
