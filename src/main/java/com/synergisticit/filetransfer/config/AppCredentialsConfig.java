package com.synergisticit.filetransfer.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.HashMap;
import java.util.Map;

/**
 * Supplies the "secrets" map from ordinary Spring configuration instead of AWS Secrets Manager.
 *
 * Values resolve through the normal Spring property chain, so all of these work with no code change:
 *   - application-{profile}.properties
 *   - environment variables (APP_CREDENTIALS_S3_BUCKET_NAME, ...)
 *   - Kubernetes Secrets mounted as environment variables
 *   - command line (--app.credentials.s3-bucket-name=...)
 *
 * Downstream consumers are unchanged: S3Service, AzureConfig and AzureBlobService still inject
 * @Qualifier("secrets") Map<String, String> and read the same keys as before.
 */
@Slf4j
@Configuration
@Profile("!test")
public class AppCredentialsConfig {

    @Bean("secrets")
    public Map<String, String> secrets(
            @Value("${app.credentials.s3-bucket-name:}") String s3BucketName,
            @Value("${app.credentials.azure-storage-account-name:}") String azureAccountName,
            @Value("${app.credentials.azure-storage-account-key:}") String azureAccountKey,
            @Value("${app.credentials.azure-container-name:}") String azureContainerName) {

        Map<String, String> credentials = new HashMap<>();
        credentials.put("s3_bucket_name", s3BucketName);
        credentials.put("azure_storage_account_name", azureAccountName);
        credentials.put("azure_storage_account_key", azureAccountKey);
        credentials.put("azure_container_name", azureContainerName);

        credentials.forEach((key, value) -> {
            if (value == null || value.isBlank()) {
                log.warn("Credential [{}] is not configured - set app.credentials.* or the matching env var", key);
            }
        });

        log.info("Loaded {} credential entries from application configuration", credentials.size());
        return credentials;
    }
}
