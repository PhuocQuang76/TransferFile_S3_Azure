package com.synergisticit.filetransfer.integration;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;

import java.net.URI;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractCloudIntegrationTest {

    protected static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.0.0")
    ).withServices(LocalStackContainer.Service.S3);

    @BeforeAll
    static void startContainers() {
        LOCALSTACK.start();
        
        // Create source and destination buckets in LocalStack S3
        S3AsyncClient s3AsyncClient = S3AsyncClient.builder()
                .endpointOverride(LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey())
                ))
                .region(Region.of(LOCALSTACK.getRegion()))
                .build();

        s3AsyncClient.createBucket(b -> b.bucket("source-bucket")).join();
        s3AsyncClient.createBucket(b -> b.bucket("destination-bucket")).join();
    }

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("aws.s3.endpoint", () -> LOCALSTACK.getEndpointOverride(LocalStackContainer.Service.S3).toString());
        registry.add("aws.s3.region", LOCALSTACK::getRegion);
        registry.add("aws.s3.access-key", LOCALSTACK::getAccessKey);
        registry.add("aws.s3.secret-key", LOCALSTACK::getSecretKey);
        registry.add("storage.source-bucket", () -> "source-bucket");
        registry.add("storage.destination-bucket", () -> "destination-bucket");
    }
}