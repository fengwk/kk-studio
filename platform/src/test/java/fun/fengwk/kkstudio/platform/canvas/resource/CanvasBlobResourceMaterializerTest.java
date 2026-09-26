package fun.fengwk.kkstudio.platform.canvas.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasDocument;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

class CanvasBlobResourceMaterializerTest {

  private final StorageUploadService uploadService = mock(StorageUploadService.class);
  private final StorageBlobManager blobManager = mock(StorageBlobManager.class);
  private final CanvasStore canvasStore = mock(CanvasStore.class);
  private final CanvasFunctionResourcePinRepository pinRepository =
      mock(CanvasFunctionResourcePinRepository.class);
  private final CanvasResourceRepository resourceRepository = mock(CanvasResourceRepository.class);
  private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
  private final UUID canvasId = UUID.randomUUID();
  private final UUID resourceId = UUID.randomUUID();
  private final UUID uploadId = UUID.randomUUID();
  private final UUID blobId = UUID.randomUUID();
  private final CanvasBlobResourceMaterializer materializer =
      new CanvasBlobResourceMaterializer(
          uploadService,
          blobManager,
          canvasStore,
          pinRepository,
          resourceRepository,
          transactionTemplate);

  @BeforeEach
  void setUp() {
    when(resourceRepository.findById(canvasId, resourceId)).thenReturn(Optional.empty());
    StorageUploadService.StagedUpload upload =
        new StorageUploadService.StagedUpload(
            uploadId, blobId, "stored.bin", "image/png", 1L, "a".repeat(64));
    when(uploadService.stage(any(), any(), any(), anyLong())).thenReturn(upload);
    when(canvasStore.lockDocument(canvasId)).thenReturn(Optional.of(mock(CanvasDocument.class)));
    when(pinRepository.findRunningOutputPins(canvasId, resourceId))
        .thenReturn(List.of(mock(CanvasFunctionResourcePin.class)));
    when(uploadService.lockReady(uploadId))
        .thenReturn(new StorageUploadService.ReadyUpload(blobId, "stored.bin"));
    when(resourceRepository.addIfAbsent(any())).thenReturn(true);
    when(transactionTemplate.execute(any()))
        .thenAnswer(
            invocation ->
                invocation
                    .<TransactionCallback<CanvasResource>>getArgument(0)
                    .doInTransaction(mock(TransactionStatus.class)));
  }

  @Test
  void stagesAndTransfersUploadOwnershipInShortTransaction() {
    CanvasResource resource = materialize();

    assertEquals(blobId, resource.blobId());
    verify(blobManager).retain(blobId);
    verify(uploadService).delete(uploadId);
  }

  @Test
  void existingResourceReturnsWithoutStaging() {
    CanvasResource existing =
        new CanvasResource(
            resourceId, canvasId, null, null, blobId, "existing", null, Instant.now());
    when(resourceRepository.findById(canvasId, resourceId)).thenReturn(Optional.of(existing));

    assertSame(existing, materialize());
    verify(uploadService, never()).stage(any(), any(), any(), anyLong());
  }

  @Test
  void rejectsMissingCanvasOrInvalidRunningPin() {
    when(canvasStore.lockDocument(canvasId)).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, this::materialize);

    when(canvasStore.lockDocument(canvasId)).thenReturn(Optional.of(mock(CanvasDocument.class)));
    when(pinRepository.findRunningOutputPins(canvasId, resourceId)).thenReturn(List.of());
    assertThrows(IllegalStateException.class, this::materialize);
    verify(uploadService, times(2)).delete(uploadId);
  }

  @Test
  void insertRaceUsesWinnerAndConsumesUploadWithoutRetain() {
    CanvasResource winner =
        new CanvasResource(resourceId, canvasId, null, null, blobId, "winner", null, Instant.now());
    when(resourceRepository.addIfAbsent(any())).thenReturn(false);
    when(resourceRepository.findById(canvasId, resourceId))
        .thenReturn(Optional.empty(), Optional.of(winner));

    assertSame(winner, materialize());
    verify(blobManager, never()).retain(any());
    verify(uploadService).delete(uploadId);
  }

  @Test
  void missingWinnerFails() {
    when(resourceRepository.addIfAbsent(any())).thenReturn(false);
    assertThrows(IllegalStateException.class, this::materialize);
  }

  private CanvasResource materialize() {
    return materializer.materialize(
        canvasId, resourceId, "output.bin", new ByteArrayInputStream(new byte[] {1}));
  }
}
