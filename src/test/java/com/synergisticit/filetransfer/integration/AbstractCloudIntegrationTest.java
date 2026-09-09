package com.synergisticit.filetransfer.integration;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.util.Map;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractCloudIntegrationTest {

    protected static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.0.0")
    ).withServices(LocalStackContainer.Service.S3);

    @BeforeAll
    static void startContainers() {
        if (!LOCALSTACK.isRunning()) {
            LOCALSTACK.start();
        }

        // Initialize S3 buckets inside LocalStack
        try (S3AsyncClient s3AsyncClient = S3AsyncClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())
                ))
                .region(Region.of(LOCALSTACK.getRegion()))
                .build()) {

            // Create buckets safely, ignoring errors if they already exist
            s3AsyncClient.createBucket(b -> b.bucket("source-bucket"))
                    .exceptionally(ex -> null)
                    .join();

            s3AsyncClient.createBucket(b -> b.bucket("destination-bucket"))
                    .exceptionally(ex -> null)
                    .join();
        }
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.s3.endpoint", () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3).toString());
        registry.add("aws.s3.region", LOCALSTACK::getRegion);
        registry.add("aws.access-key", LOCALSTACK::getAccessKey);
        registry.add("aws.secret-key", LOCALSTACK::getSecretKey);
        registry.add("aws.s3.bucket-name", () -> "source-bucket");
        registry.add("storage.source-bucket", () -> "source-bucket");
        registry.add("storage.destination-bucket", () -> "destination-bucket");
    }
}