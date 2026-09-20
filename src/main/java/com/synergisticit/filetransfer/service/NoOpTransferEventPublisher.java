package com.synergisticit.filetransfer.service;

import com.synergisticit.filetransfer.model.TransferResult;
import com.synergisticit.filetransfer.service.interfaces.TransferEventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Used when Event Hubs is not configured (azure.eventhub.enabled=false), so TransferService
 * always has a publisher to call and the application still starts without Event Hub credentials.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "azure.eventhub.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpTransferEventPublisher implements TransferEventPublisher {

    @Override
    public Mono<Void> publish(TransferResult result) {
        log.debug("Event publishing disabled - not announcing [{}]", result.getFileName());
        return Mono.empty();
    }
}
