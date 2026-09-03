package com.synergisticit.filetransfer.config;

import com.synergisticit.filetransfer.service.interfaces.StorageDestination;
import com.synergisticit.filetransfer.service.interfaces.StorageSource;
import com.synergisticit.filetransfer.service.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Configuration class for TransferService bean creation.
 * 
 * This configuration enables the Open/Closed Principle by allowing dynamic selection
 * of storage providers via configuration properties without modifying code.
 * 
 * To add a new storage provider (e.g., Google Cloud Storage):
 * 1. Create a new service class implementing StorageSource or StorageDestination
 * 2. Add it as a Spring bean with @Service annotation
 * 3. Update application.properties to use the new provider
 * 4. No code modification needed in this configuration class
 */
@Slf4j
@Configuration
public class TransferConfig {

    /**
     * Creates TransferService bean with dynamically selected storage providers.
     * 
     * Uses Map-based injection to get all available storage providers and selects
     * the appropriate ones based on configuration properties.
     * 
     * @param sources Map of all StorageSource beans (bean name -> implementation)
     * @param destinations Map of all StorageDestination beans (bean name -> implementation)
     * @param sourceType Configuration property specifying which source to use
     * @param destinationType Configuration property specifying which destination to use
     * @param concurrencyLimit Maximum concurrent file transfers
     * @param deleteAfterTransfer Whether to delete source files after successful transfer
     * @return Configured TransferService instance
     */
    @Bean
    public TransferService transferService(
            Map<String, StorageSource> sources,
            Map<String, StorageDestination> destinations,
            @Value("${storage.source:s3Service}") String sourceType,
            @Value("${storage.destination:azureBlobService}") String destinationType,
            @Value("${transfer.concurrency:5}") int concurrencyLimit,
            @Value("${transfer.delete-after-transfer:false}") boolean deleteAfterTransfer) {
        
        log.info("Configuring TransferService with source: [{}], destination: [{}]", sourceType, destinationType);
        
        StorageSource source = sources.get(sourceType);
        if (source == null) {
            throw new IllegalArgumentException(
                String.format("No StorageSource bean found with name '%s'. Available sources: %s", 
                    sourceType, sources.keySet()));
        }
        
        StorageDestination destination = destinations.get(destinationType);
        if (destination == null) {
            throw new IllegalArgumentException(
                String.format("No StorageDestination bean found with name '%s'. Available destinations: %s", 
                    destinationType, destinations.keySet()));
        }
        
        log.info("Selected source: [{}] (identifier: {}), destination: [{}] (identifier: {})",
                sourceType, source.getSourceIdentifier(),
                destinationType, destination.getDestinationIdentifier());
        
        return new TransferService(source, destination, concurrencyLimit, deleteAfterTransfer);
    }
}
