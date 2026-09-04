package com.synergisticit.filetransfer.unit.service;

import com.synergisticit.filetransfer.enums.TransferStatus;
import com.synergisticit.filetransfer.model.StorageMetadata;
import com.synergisticit.filetransfer.model.StorageObject;
import com.synergisticit.filetransfer.model.TransferResult;
import com.synergisticit.filetransfer.service.TransferService;
import com.synergisticit.filetransfer.service.interfaces.StorageDestination;
import com.synergisticit.filetransfer.service.interfaces.StorageSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.FileNotFoundException;
import java.nio.file.AccessDeniedException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private StorageSource source;

    @Mock
    private StorageDestination destination;

    private TransferService transferService;

    @BeforeEach
    void setUp() {
        transferService = new TransferService(source, destination, 5, false);
        lenient().when(source.getObjectStream(anyString())).thenReturn(Flux.empty());
    }

    @Test
    @DisplayName("Should successfully transfer file when destination file does not exist")
    void testExecuteTransfer_Success() {
        StorageObject mockFile = StorageObject.builder()
                .key("test.txt")
                .size(100L)
                .build();
        StorageMetadata metadata = StorageMetadata.builder()
                .contentType("text/plain")
                .contentLength(100L)
                .build();

        when(source.listObjects(null, null)).thenReturn(Flux.just(mockFile));
        when(source.getObjectMetadata("test.txt")).thenReturn(Mono.just(metadata));
        when(source.getSourceIdentifier()).thenReturn("test-bucket");
        when(destination.existsAndMatchesSize("test.txt", 100L)).thenReturn(Mono.just(false));
        when(destination.uploadStream(eq("test.txt"), any(), eq(100L), eq("text/plain"))).thenReturn(Mono.empty());

        StepVerifier.create(transferService.executeTransfer(null, null, false))
                .assertNext(summary -> {
                    assertEquals(1, summary.getTotalDiscovered());
                    assertEquals(1, summary.getTotalTransferred());
                    assertEquals(0, summary.getTotalSkipped());
                    assertEquals(0, summary.getTotalFailed());
                    assertTrue(summary.getTotalDurationMs() >= 0);
                })
                .verifyComplete();

        verify(source).listObjects(null, null);
        verify(source).getObjectMetadata("test.txt");
        verify(destination).existsAndMatchesSize("test.txt", 100L);
        verify(destination).uploadStream(eq("test.txt"), any(), eq(100L), eq("text/plain"));
    }

    @Test
    @DisplayName("Should skip transfer when file already exists and overwrite is false")
    void testExecuteTransfer_SkipExistingFile() {
        StorageObject mockFile = StorageObject.builder()
                .key("test.txt")
                .size(100L)
                .build();
        StorageMetadata metadata = StorageMetadata.builder()
                .contentType("text/plain")
                .contentLength(100L)
                .build();

        when(source.listObjects(null, null)).thenReturn(Flux.just(mockFile));
        when(source.getObjectMetadata("test.txt")).thenReturn(Mono.just(metadata));
        when(source.getSourceIdentifier()).thenReturn("test-bucket");
        when(destination.existsAndMatchesSize("test.txt", 100L)).thenReturn(Mono.just(true));

        StepVerifier.create(transferService.executeTransfer(null, null, false))
                .assertNext(summary -> {
                    assertEquals(1, summary.getTotalDiscovered());
                    assertEquals(0, summary.getTotalTransferred());
                    assertEquals(1, summary.getTotalSkipped());
                    assertEquals(0, summary.getTotalFailed());
                })
                .verifyComplete();

        verify(destination, never()).uploadStream(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("Should overwrite existing file when overwrite parameter is true")
    void testExecuteTransfer_OverwriteExistingFile() {
        StorageObject mockFile = StorageObject.builder()
                .key("test.txt")
                .size(100L)
                .build();
        StorageMetadata metadata = StorageMetadata.builder()
                .contentType("text/plain")
                .contentLength(100L)
                .build();

        when(source.listObjects(null, null)).thenReturn(Flux.just(mockFile));
        when(source.getObjectMetadata("test.txt")).thenReturn(Mono.just(metadata));
        when(source.getSourceIdentifier()).thenReturn("test-bucket");
        when(destination.uploadStream(eq("test.txt"), any(), eq(100L), eq("text/plain"))).thenReturn(Mono.empty());

        StepVerifier.create(transferService.executeTransfer(null, null, true))
                .assertNext(summary -> {
                    assertEquals(1, summary.getTotalTransferred());
                    assertEquals(0, summary.getTotalSkipped());
                })
                .verifyComplete();

        verify(destination, never()).existsAndMatchesSize(any(), anyLong());
        verify(destination).uploadStream(eq("test.txt"), any(), eq(100L), eq("text/plain"));
    }

    @Test
    @DisplayName("Should record failure when uploadStream throws an error")
    void testExecuteTransfer_UploadFailure() {
        StorageObject mockFile = StorageObject.builder()
                .key("test.txt")
                .size(100L)
                .build();
        StorageMetadata metadata = StorageMetadata.builder()
                .contentType("text/plain")
                .contentLength(100L)
                .build();

        when(source.listObjects(null, null)).thenReturn(Flux.just(mockFile));
        when(source.getObjectMetadata("test.txt")).thenReturn(Mono.just(metadata));
        when(source.getSourceIdentifier()).thenReturn("test-bucket");
        when(destination.existsAndMatchesSize("test.txt", 100L)).thenReturn(Mono.just(false));
        when(destination.uploadStream(eq("test.txt"), any(), eq(100L), eq("text/plain")))
                .thenReturn(Mono.error(new RuntimeException("Upload failed")));

        StepVerifier.create(transferService.executeTransfer(null, null, false))
                .assertNext(summary -> {
                    assertEquals(1, summary.getTotalDiscovered());
                    assertEquals(0, summary.getTotalTransferred());
                    assertEquals(0, summary.getTotalSkipped());
                    assertEquals(1, summary.getTotalFailed());

                    TransferResult failedResult = summary.getResults().get(0);
                    assertEquals(TransferStatus.FAILED, failedResult.getStatus());
                    assertEquals(500, failedResult.getErrorCode());
                    assertEquals("Upload failed", failedResult.getErrorMessage());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should record failure when fetching object metadata fails")
    void testExecuteTransfer_MetadataFetchFailure() {
        StorageObject mockFile = StorageObject.builder()
                .key("test.txt")
                .size(100L)
                .build();

        when(source.listObjects(null, null)).thenReturn(Flux.just(mockFile));
        when(source.getObjectMetadata("test.txt"))
                .thenReturn(Mono.error(new RuntimeException("Metadata fetch failed")));
        when(source.getSourceIdentifier()).thenReturn("test-bucket");

        StepVerifier.create(transferService.executeTransfer(null, null, false))
                .assertNext(summary -> {
                    assertEquals(1, summary.getTotalFailed());
                    TransferResult failedResult = summary.getResults().get(0);
                    assertEquals(TransferStatus.FAILED, failedResult.getStatus());
                    assertEquals("Metadata fetch failed", failedResult.getErrorMessage());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should process multiple files sequentially in stream pipeline")
    void testExecuteTransfer_MultipleFiles() {
        StorageObject file1 = StorageObject.builder().key("file1.txt").size(100L).build();
        StorageObject file2 = StorageObject.builder().key("file2.txt").size(200L).build();
        StorageObject file3 = StorageObject.builder().key("file3.txt").size(300L).build();

        StorageMetadata metadata1 = StorageMetadata.builder().contentType("text/plain").contentLength(100L).build();
        StorageMetadata metadata2 = StorageMetadata.builder().contentType("text/plain").contentLength(200L).build();
        StorageMetadata metadata3 = StorageMetadata.builder().contentType("text/plain").contentLength(300L).build();

        when(source.listObjects(null, null)).thenReturn(Flux.just(file1, file2, file3));
        when(source.getObjectMetadata("file1.txt")).thenReturn(Mono.just(metadata1));
        when(source.getObjectMetadata("file2.txt")).thenReturn(Mono.just(metadata2));
        when(source.getObjectMetadata("file3.txt")).thenReturn(Mono.just(metadata3));
        when(source.getSourceIdentifier()).thenReturn("test-bucket");
        when(destination.existsAndMatchesSize(any(), anyLong())).thenReturn(Mono.just(false));
        when(destination.uploadStream(any(), any(), anyLong(), any())).thenReturn(Mono.empty());

        StepVerifier.create(transferService.executeTransfer(null, null, false))
                .assertNext(summary -> {
                    assertEquals(3, summary.getTotalDiscovered());
                    assertEquals(3, summary.getTotalTransferred());
                    assertEquals(0, summary.getTotalFailed());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should map FileNotFoundException to HTTP 404 error code")
    void testDetermineErrorCode_FileNotFound() {
        StorageObject mockFile = StorageObject.builder().key("test.txt").size(100L).build();

        when(source.listObjects(null, null)).thenReturn(Flux.just(mockFile));
        when(source.getObjectMetadata("test.txt"))
                .thenReturn(Mono.error(new FileNotFoundException("File not found")));
        when(source.getSourceIdentifier()).thenReturn("test-bucket");

        StepVerifier.create(transferService.executeTransfer(null, null, false))
                .assertNext(summary -> {
                    assertEquals(1, summary.getTotalFailed());
                    TransferResult failedResult = summary.getResults().get(0);
                    assertEquals(404, failedResult.getErrorCode());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should map AccessDeniedException to HTTP 403 error code")
    void testDetermineErrorCode_AccessDenied() {
        StorageObject mockFile = StorageObject.builder().key("test.txt").size(100L).build();

        when(source.listObjects(null, null)).thenReturn(Flux.just(mockFile));
        when(source.getObjectMetadata("test.txt"))
                .thenReturn(Mono.error(new AccessDeniedException("Access denied")));
        when(source.getSourceIdentifier()).thenReturn("test-bucket");

        StepVerifier.create(transferService.executeTransfer(null, null, false))
                .assertNext(summary -> {
                    assertEquals(1, summary.getTotalFailed());
                    TransferResult failedResult = summary.getResults().get(0);
                    assertEquals(403, failedResult.getErrorCode());
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Should return summary with zero counts when no source objects are discovered")
    void testExecuteTransfer_EmptySource() {
        when(source.listObjects(eq("empty/"), eq(".csv"))).thenReturn(Flux.empty());

        StepVerifier.create(transferService.executeTransfer("empty/", ".csv", false))
                .assertNext(summary -> {
                    assertEquals(0, summary.getTotalDiscovered());
                    assertEquals(0, summary.getTotalTransferred());
                    assertEquals(0, summary.getTotalSkipped());
                    assertEquals(0, summary.getTotalFailed());
                })
                .verifyComplete();
    }
}