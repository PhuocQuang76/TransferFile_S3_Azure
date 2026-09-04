package com.synergisticit.filetransfer.config;

import com.synergisticit.filetransfer.service.interfaces.StorageDestination;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
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
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

@Configuration
@Profile("test")
public class TestInfrastructureConfig {

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
        return S3AsyncClient.builder()
                .endpointOverride(URI.create("http://localhost:4566"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create("test", "test")))
                .region(Region.US_EAST_1)
                .forcePathStyle(true)
                .build();
    }

    @Bean("testDestination")
    public StorageDestination testDestination(S3AsyncClient s3AsyncClient) {
        return new StorageDestination() {
            @Override
            public Mono<Boolean> existsAndMatchesSize(String path, long expectedSize) {
                HeadObjectRequest request = HeadObjectRequest.builder()
                        .bucket("destination-bucket")
                        .key(path)
                        .build();

                return Mono.fromFuture(s3AsyncClient.headObject(request))
                        .map(response -> response.contentLength() == expectedSize)
                        .onErrorResume(e -> Mono.just(false));
            }

            @Override
            public Mono<Void> uploadStream(String path, Flux<ByteBuffer> byteStream, long contentLength, String contentType) {
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
                                                .build(),
                                        AsyncRequestBody.fromBytes(payload)
                                )).then();
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
