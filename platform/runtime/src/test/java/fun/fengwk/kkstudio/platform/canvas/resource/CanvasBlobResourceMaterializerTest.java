package fun.fengwk.kkstudio.platform.canvas.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import fun.fengwk.kkstudio.canvas.CanvasFunctionRun;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunStatus;
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
  private final CanvasFunctionRunRepository runRepository = mock(CanvasFunctionRunRepository.class);
  private final CanvasFunctionResourcePinRepository pinRepository =
      mock(CanvasFunctionResourcePinRepository.class);
  private final CanvasResourceRepository resourceRepository = mock(CanvasResourceRepository.class);
  private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
  private final UUID canvasId = UUID.randomUUID();
  private final UUID nodeId = UUID.randomUUID();
  private final UUID requestId = UUID.randomUUID();
  private final UUID resourceId = UUID.randomUUID();
  private final UUID uploadId = UUID.randomUUID();
  private final UUID blobId = UUID.randomUUID();
  private final CanvasBlobResourceMaterializer materializer =
      new CanvasBlobResourceMaterializer(
          uploadService,
          blobManager,
          canvasStore,
          runRepository,
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
    CanvasFunctionRun run =
        new CanvasFunctionRun(
            nodeId,
            requestId,
            CanvasFunctionRunStatus.RUNNING,
            0,
            null,
            "lease-token-1",
            Instant.now().plusSeconds(60),
            "RUNNING",
            "{}",
            null,
            Instant.now(),
            Instant.now());
    when(runRepository.findByNodeIdForUpdate(nodeId)).thenReturn(Optional.of(run));
    when(runRepository.ownsRunningRequest(canvasId, nodeId, requestId, "lease-token-1"))
        .thenReturn(true);
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
    verify(pinRepository)
        .addAll(
            List.of(
                new CanvasFunctionResourcePin(
                    canvasId,
                    nodeId,
                    requestId,
                    resourceId,
                    CanvasFunctionResourcePin.Role.OUTPUT)));
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
  void rejectsMissingCanvasOrInvalidRunningRun() {
    when(canvasStore.lockDocument(canvasId)).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, this::materialize);

    when(canvasStore.lockDocument(canvasId)).thenReturn(Optional.of(mock(CanvasDocument.class)));
    when(runRepository.findByNodeIdForUpdate(nodeId)).thenReturn(Optional.empty());
    assertThrows(IllegalStateException.class, this::materialize);
    verify(uploadService, never()).stage(any(), any(), any(), anyLong());
  }

  @Test
  void insertRaceUsesWinnerAndConsumesUploadWithoutRetain() {
    CanvasResource winner =
        new CanvasResource(resourceId, canvasId, null, null, blobId, "winner", null, Instant.now());
    when(resourceRepository.addIfAbsent(any())).thenReturn(false);
    when(resourceRepository.findById(canvasId, resourceId))
        .thenReturn(Optional.empty(), Optional.empty(), Optional.of(winner));

    assertSame(winner, materialize());
    verify(blobManager, never()).retain(any());
    verify(uploadService).delete(uploadId);
  }

  @Test
  void missingWinnerFails() {
    when(resourceRepository.addIfAbsent(any())).thenReturn(false);
    assertThrows(IllegalStateException.class, this::materialize);
  }

  @Test
  void materializesInlineTextWithoutBlobOrStaging() {
    CanvasResource resource =
        materializer.materializeText(
            canvasId, nodeId, requestId, "lease-token-1", resourceId, "report.txt", "summary text");

    assertEquals("summary text", resource.textContent());
    assertTrue(resource.isText());
    assertEquals("report.txt", resource.name());
    verify(pinRepository)
        .addAll(
            List.of(
                new CanvasFunctionResourcePin(
                    canvasId,
                    nodeId,
                    requestId,
                    resourceId,
                    CanvasFunctionResourcePin.Role.OUTPUT)));
    verify(uploadService, never()).stage(any(), any(), any(), anyLong());
    verify(blobManager, never()).retain(any());
  }

  @Test
  void existingTextResourceReturnsWithoutInserting() {
    CanvasResource existing =
        new CanvasResource(
            resourceId, canvasId, null, null, null, "report.txt", "cached", Instant.now());
    when(resourceRepository.findById(canvasId, resourceId)).thenReturn(Optional.of(existing));

    assertSame(
        existing,
        materializer.materializeText(
            canvasId, nodeId, requestId, "lease-token-1", resourceId, "report.txt", "cached"));
    verify(resourceRepository, never()).addIfAbsent(any());
  }

  /** stage 后出现同 request winner 时仍需 fence，临时 upload 被消费但不新增引用。 */
  @Test
  void winnerAppearingDuringStageIsReturnedWithoutRetain() {
    CanvasResource winner =
        new CanvasResource(resourceId, canvasId, null, null, blobId, "winner", null, Instant.now());
    when(resourceRepository.findById(canvasId, resourceId))
        .thenReturn(Optional.empty(), Optional.of(winner));
    assertSame(winner, materialize());
    verify(uploadService).delete(uploadId);
    verify(blobManager, never()).retain(any());
  }

  /** 围栏在 stage 后失败时必清理；清理失败不能掩盖原始 ownership 异常。 */
  @Test
  void postStageFenceFailureDiscardsBestEffort() {
    when(runRepository.ownsRunningRequest(canvasId, nodeId, requestId, "lease-token-1"))
        .thenReturn(true, false);
    doThrow(new IllegalStateException("cleanup failed")).when(uploadService).delete(uploadId);
    IllegalStateException failure = assertThrows(IllegalStateException.class, this::materialize);
    assertTrue(failure.getMessage().contains("live lease owner"));
    verify(resourceRepository, never()).addIfAbsent(any());
    verify(pinRepository, never()).addAll(any());
    verify(blobManager, never()).retain(any());
  }

  /** TEXT 边界校验不触碰 DB；insert 冲突只使用真实 winner，无 winner 则回滚失败。 */
  @Test
  void textValidationAndInsertRace() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            materializer.materializeText(
                canvasId, nodeId, requestId, "lease-token-1", resourceId, "text", null));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            materializer.materializeText(
                canvasId,
                nodeId,
                requestId,
                "lease-token-1",
                resourceId,
                "text",
                "x".repeat(1024 * 1024 + 1)));
    CanvasResource winner =
        new CanvasResource(resourceId, canvasId, null, null, null, "winner", "", Instant.now());
    when(resourceRepository.addIfAbsent(any())).thenReturn(false);
    when(resourceRepository.findById(canvasId, resourceId))
        .thenReturn(Optional.empty(), Optional.of(winner));
    assertSame(
        winner,
        materializer.materializeText(
            canvasId, nodeId, requestId, "lease-token-1", resourceId, "text", ""));
    when(resourceRepository.findById(canvasId, resourceId)).thenReturn(Optional.empty());
    assertThrows(
        IllegalStateException.class,
        () ->
            materializer.materializeText(
                canvasId, nodeId, requestId, "lease-token-1", resourceId, "text", ""));
  }

  private CanvasResource materialize() {
    return materializer.materializeBlob(
        canvasId,
        nodeId,
        requestId,
        "lease-token-1",
        resourceId,
        "output.bin",
        new ByteArrayInputStream(new byte[] {1}));
  }
}
