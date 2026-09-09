package com.synergisticit.filetransfer.integration;

import com.synergisticit.filetransfer.model.TransferSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;

import static org.junit.jupiter.api.Assertions.*;

@AutoConfigureWebTestClient
class FileTransferIntegrationTest extends AbstractCloudIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private S3AsyncClient s3AsyncClient;

    @BeforeEach
    void setupTestData() {
        // Upload a sample file to source-bucket inside LocalStack
        s3AsyncClient.putObject(
                b -> b.bucket("source-bucket").key("test-data/report.pdf"),
                AsyncRequestBody.fromString("Sample PDF Content")
        ).join();
    }

    @Test
    @DisplayName("End-To-End: Should transfer object from source to destination bucket via REST API")
    void testEndToEndFileTransfer() {
        // Act: Invoke REST API endpoint
        TransferSummary summary = webTestClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/transfer")
                        .queryParam("prefix", "test-data/")
                        .queryParam("suffix", ".pdf")
                        .queryParam("overwrite", false)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectBody(TransferSummary.class)
                .returnResult()
                .getResponseBody();

        // Assert: Verify TransferSummary details
        assertNotNull(summary);
        assertEquals(1, summary.getTotalDiscovered());
        assertEquals(1, summary.getTotalTransferred());
        assertEquals(0, summary.getTotalFailed());

        // Assert: Verify the file exists in the destination bucket
        Boolean fileExistsInDestination = s3AsyncClient.headObject(
                HeadObjectRequest.builder()
                        .bucket("destination-bucket")
                        .key("test-data/report.pdf")
                        .build()
        ).handle((res, ex) -> ex == null).join();

        assertTrue(fileExistsInDestination, "File should exist in destination bucket after transfer");
    }
}