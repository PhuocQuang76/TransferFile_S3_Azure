package com.synergisticit.filetransfer.service.interfaces;

import com.synergisticit.filetransfer.model.TransferResult;
import reactor.core.publisher.Mono;

/**
 * Publishes one event per transferred file so other teams can react without
 * this connector knowing they exist.
 *
 * Declared as a port so TransferService stays free of messaging concerns, exactly as it stays
 * free of cloud storage SDKs. The Event Hubs implementation lives alongside the other adapters.
 *
 * Implementations MUST NOT fail a transfer because publishing failed: the bytes are already in
 * the destination and the source may already be deleted, so failing here would trigger a
 * pointless re-transfer. Log it and complete empty instead.
 */
public interface TransferEventPublisher {

    /**
     * Publishes an event describing one file's outcome.
     * @param result the per-file outcome to announce
     * @return a Mono that completes when the publish attempt is done - successfully or not
     */
    Mono<Void> publish(TransferResult result);
}
