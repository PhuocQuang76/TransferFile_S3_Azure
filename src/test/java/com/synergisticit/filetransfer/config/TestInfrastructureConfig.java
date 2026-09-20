package com.synergisticit.filetransfer.config;

import com.synergisticit.filetransfer.service.interfaces.StorageDestination;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

@Configuration
@Profile("test")
public class TestInfrastructureConfig {

    @Value("${aws.s3.endpoint:http://localhost:4566}")
    private String s3Endpoint;

    @Value("${aws.access-key:test}")
    private String accessKey;

    @Value("${aws.secret-key:test}")
    private String secretKey;

    @Value("${aws.s3.region:us-east-1}")
    private String region;

    @Bean(name = "secrets")
    @Primary
    public Map<String, String> testSecrets() {
        Map<String, String> secrets = new HashMap<>();
        secrets.put("s3_bucket_name", "source-bucket");
        secrets.put("azure_container_name", "destination-bucket");
        secrets.put("mysql_username", "sa");
        secrets.put("mysql_password", "");
        return secrets;
    }

    @Bean
    @Primary
    public DataSource testDataSource() {
        return DataSourceBuilder.create()
                .type(HikariDataSource.class)
                .url("jdbc:h2:mem:testdb;MODE=MySQL;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
    }

    @Bean
    @Primary
    public S3AsyncClient localstackS3Client() {
        String resolvedAccessKey = (accessKey == null || accessKey.isBlank()) ? "test" : accessKey;
        String resolvedSecretKey = (secretKey == null || secretKey.isBlank()) ? "test" : secretKey;

        return S3AsyncClient.builder()
                .endpointOverride(URI.create(s3Endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(resolvedAccessKey, resolvedSecretKey)))
                .region(Region.of(region))
                .forcePathStyle(true)
                .build();
    }

    @Bean("testDestination")
    public StorageDestination testDestination(S3AsyncClient s3AsyncClient) {
        return new StorageDestination() {

            @Override
            public Mono<Boolean> matchesSource(String path, String sourceETag, long expectedSize) {
                HeadObjectRequest request = HeadObjectRequest.builder()
                        .bucket("destination-bucket")
                        .key(path)
                        .build();

                return Mono.fromFuture(s3AsyncClient.headObject(request))
                        .map(response -> {
                            String stored = response.metadata().get("source_etag");
                            return stored == null
                                    ? response.contentLength() == expectedSize
                                    : stored.replace("\"", "").equals(sourceETag == null ? "" : sourceETag.replace("\"", ""));
                        })
                        .onErrorResume(NoSuchKeyException.class, e -> Mono.just(false));
            }

            @Override
            public Mono<String> uploadStream(String path, Flux<ByteBuffer> byteStream, long contentLength,
                                             String contentType, String sourceETag) {
                return byteStream.collectList()
                        .flatMap(chunks -> {
                            try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                                for (ByteBuffer chunk : chunks) {
                                    byte[] bytes = new byte[chunk.remaining()];
                                    chunk.get(bytes);
                                    output.write(bytes);
                                }
                                byte[] payload = output.toByteArray();
                                return Mono.fromFuture(s3AsyncClient.putObject(
                                        PutObjectRequest.builder()
                                                .bucket("destination-bucket")
                                                .key(path)
                                                .contentLength((long) payload.length)
                                                .contentType(contentType)
                                                .metadata(Map.of("source_etag",
                                                        sourceETag == null ? "" : sourceETag.replace("\"", "")))
                                                .build(),
                                        AsyncRequestBody.fromBytes(payload)
                                )).map(PutObjectResponse::eTag);
                            } catch (Exception e) {
                                return Mono.error(e);
                            }
                        });
            }

            @Override
            public String getDestinationIdentifier() {
                return "destination-bucket";
            }
        };
    }
}
