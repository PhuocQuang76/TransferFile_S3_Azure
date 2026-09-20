package com.synergisticit.filetransfer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

/**
 * Configuration for AWS SDK clients.
 * Follows Single Responsibility Principle - only handles AWS client bean creation.
 *
 * Credentials are resolved by the AWS SDK default credential provider chain, in order:
 *   1. environment variables (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY)
 *   2. the shared profile in ~/.aws/credentials
 *   3. the container or EC2 instance IAM role
 *
 * Nothing needs configuring here for local development: whatever `aws configure` set up is used.
 */
@Configuration
// Prevents real AWS client creation in unit/integration tests, where TestInfrastructureConfig
// supplies a LocalStack-pointed @Primary client instead.
@Profile("!test")
public class AWSConfig {

    @Value("${aws.region:us-east-1}")
    private String region;

    /**
     * Builds the asynchronous S3 client used by S3Service to list, stream and delete objects.
     */
    @Bean
    public S3AsyncClient s3AsyncClient() {
        return S3AsyncClient.builder()
                .region(Region.of(region))
                .build();
    }
}
