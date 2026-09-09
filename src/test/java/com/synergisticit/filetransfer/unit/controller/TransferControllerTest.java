package com.synergisticit.filetransfer.unit.controller;

import com.synergisticit.filetransfer.controler.TransferController;
import com.synergisticit.filetransfer.model.TransferSummary;
import com.synergisticit.filetransfer.service.TransferService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.WebFluxTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

//Tells Spring Boot to start a lightweight WebFlux test slice specifically for TransferController.
@WebFluxTest(TransferController.class) //
class TransferControllerTest {

    //Injects Spring WebFlux’s reactive HTTP client designed specifically for testing REST controllers.

    //Allows sending mock HTTP requests (like POST) to the controller and asserting responses.
    @Autowired
    private WebTestClient webTestClient;

    //Creates a Mockito mock of TransferService and registers it directly inside the Spring application context.
    //Replaces the real business logic service with a controllable dummy bean inside the controller's dependency graph.
    @MockBean
    private TransferService transferService;

    @Test
    @DisplayName("POST /api/v1/transfer - Should trigger transfer and return 200 OK with summary")
    void testTriggerTransfer_Success() {
        // Arrange
        TransferSummary mockSummary = TransferSummary.builder()
                .totalDiscovered(2)
                .totalTransferred(2)
                .totalSkipped(0)
                .totalFailed(0)
                .totalDurationMs(150L)
                .build();

        when(transferService.executeTransfer("incoming/", ".pdf", true))
                .thenReturn(Mono.just(mockSummary));

        // Act & Assert
        webTestClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/v1/transfer")
                        .queryParam("prefix", "incoming/")
                        .queryParam("extension", ".pdf")
                        .queryParam("overwrite", true)
                        .build())
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.totalDiscovered").isEqualTo(2)
                .jsonPath("$.totalTransferred").isEqualTo(2)
                .jsonPath("$.totalSkipped").isEqualTo(0)
                .jsonPath("$.totalFailed").isEqualTo(0)
                .jsonPath("$.totalDurationMs").isEqualTo(150);

        verify(transferService).executeTransfer("incoming/", ".pdf", true);
    }

    @Test
    @DisplayName("POST /api/v1/transfer - Should handle service errors and return HTTP 500")
    void testTriggerTransfer_ServiceError() {
        when(transferService.executeTransfer(null, null, false))
                .thenReturn(Mono.error(new RuntimeException("Storage connectivity issue")));

        webTestClient.post()
                .uri("/api/v1/transfer")
                .accept(MediaType.APPLICATION_JSON)
                .exchange()
                .expectStatus().is5xxServerError();

        verify(transferService).executeTransfer(null, null, false);
    }
}