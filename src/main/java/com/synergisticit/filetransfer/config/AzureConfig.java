package com.synergisticit.filetransfer.config;

import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Configuration for Azure Blob Storage clients.
 * Follows Single Responsibility Principle - only handles Azure client bean creation.
 */
@Configuration
public class AzureConfig {

    /**
     * Creates and configures the Azure Blob Service Async Client.
     * Uses credentials retrieved from AWS Secrets Manager.
     */
    @Bean
    public BlobServiceAsyncClient blobServiceAsyncClient(@Qualifier("secrets") Map<String, String> secrets) {
        String accountName = secrets.get("azure_storage_account_name");
        String accountKey = secrets.get("azure_storage_account_key");

        String connectionString = String.format(
                "DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                accountName, accountKey
        );

        return new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildAsyncClient();
    }
}
