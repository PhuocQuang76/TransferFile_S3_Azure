package com.synergisticit.filetransfer.config;

import com.azure.storage.blob.BlobServiceAsyncClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Configuration for Azure Blob Storage clients.
 * Follows Single Responsibility Principle - only handles Azure client bean creation.
 */
@Slf4j
@Configuration
public class AzureConfig {

    /**
     * Well-known public Azurite emulator credentials, documented by Microsoft.
     * Used only so the context can start when no real Azure account is configured;
     * any actual upload against it will fail unless Azurite is running locally.
     */
    private static final String AZURITE_CONNECTION_STRING =
            "DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;"
            + "AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;"
            + "BlobEndpoint=http://127.0.0.1:10000/devstoreaccount1;";

    /**
     * Creates the Azure Blob Service Async Client from the configured credentials.
     * Falls back to the Azurite emulator connection string when none are set, so the
     * application still starts for local development and smoke testing.
     */
    @Bean
    public BlobServiceAsyncClient blobServiceAsyncClient(@Qualifier("secrets") Map<String, String> secrets) {
        String accountName = secrets.get("azure_storage_account_name");
        String accountKey = secrets.get("azure_storage_account_key");

        if (isBlank(accountName) || isBlank(accountKey)) {
            log.warn("Azure credentials are not configured - falling back to the Azurite emulator endpoint. "
                    + "Uploads will fail until app.credentials.azure-storage-account-name and "
                    + "app.credentials.azure-storage-account-key are set.");
            return new BlobServiceClientBuilder()
                    .connectionString(AZURITE_CONNECTION_STRING)
                    .buildAsyncClient();
        }

        String connectionString = String.format(
                "DefaultEndpointsProtocol=https;AccountName=%s;AccountKey=%s;EndpointSuffix=core.windows.net",
                accountName, accountKey
        );

        log.info("Azure Blob client configured for account [{}]", accountName);
        return new BlobServiceClientBuilder()
                .connectionString(connectionString)
                .buildAsyncClient();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
