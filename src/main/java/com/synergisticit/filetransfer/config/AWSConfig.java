package com.synergisticit.filetransfer.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;

/**
 * Configuration for AWS SDK clients.
 * Follows Single Responsibility Principle - only handles AWS client bean creation.
 */
@Configuration
//It prevents AWS client creation in unit/integration tests.
@Profile("!test")
//AWS client creation is only active when aws.secrets.enabled=true
@ConditionalOnProperty(name = "aws.secrets.enabled", havingValue = "true")
public class AWSConfig {

    @Value("${aws.secrets.region:us-east-1}")
    private String region;

    /**
     * @Bean
        Tells Spring’s IoC container that the object returned by this method should be managed as a Spring bean. Once defined, Spring can automatically inject (@Autowired or via constructor)
     * builds the S3AsyncClient instance.
     * Initiates the builder pattern used by the AWS SDK v2 to construct configured SDK client instances.
     */
    @Bean
    public S3AsyncClient s3AsyncClient() {
        return S3AsyncClient.builder()
                .region(Region.of(region))
                .build();
    }

    /**
     * Creates and configures the Secrets Manager Client.
     * Uses AWS Default Credential Provider Chain for authentication.
     */
    @Bean
    public SecretsManagerClient secretsManagerClient() {
        return SecretsManagerClient.builder()
                .region(Region.of(region))
                .build();
    }
}

//test
