package fun.fengwk.kkstudio.platform.canvas.resource;

import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.InputStream;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas 输出先在事务外通过 {@link StorageUploadService#stage} 准备为可恢复 upload，再于短事务内把 upload owner 转移给 Canvas
 * Resource。预览已经由统一上传服务在 Blob 绑定完成后生成。相同 resourceId 幂等返回既有资源；并发竞争或事务失败会立即 best-effort 释放未消费
 * upload，统一过期清理仅作为兜底。
 */
public class CanvasBlobResourceMaterializer implements CanvasResourceMaterializer {

  private static final long MAX_MATERIALIZE_SIZE = 512L * 1024 * 1024;
  private static final String OCTET_STREAM = "application/octet-stream";

  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;
  private final CanvasStore canvasStore;
  private final CanvasFunctionResourcePinRepository pinRepository;
  private final CanvasResourceRepository resourceRepository;
  private final TransactionTemplate transactionTemplate;

  public CanvasBlobResourceMaterializer(
      StorageUploadService uploadService,
      StorageBlobManager blobManager,
      CanvasStore canvasStore,
      CanvasFunctionResourcePinRepository pinRepository,
      CanvasResourceRepository resourceRepository,
      TransactionTemplate transactionTemplate) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.pinRepository = Objects.requireNonNull(pinRepository, "pinRepository");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
  }

  @Override
  public CanvasResource materialize(
      UUID canvasId, UUID resourceId, String name, InputStream content) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(content, "content");
    CanvasResource existing = resourceRepository.findById(canvasId, resourceId).orElse(null);
    if (existing != null) {
      return existing;
    }
    StorageUploadService.StagedUpload staged =
        uploadService.stage(name, OCTET_STREAM, content, MAX_MATERIALIZE_SIZE);
    UUID uploadId = staged.uploadId();
    CanvasResource resource;
    try {
      resource =
          transactionTemplate.execute(
              status -> materializeInTransaction(canvasId, resourceId, name, uploadId));
    } catch (RuntimeException failure) {
      discardBestEffort(uploadId);
      throw failure;
    }
    return resource;
  }

  private CanvasResource materializeInTransaction(
      UUID canvasId, UUID resourceId, String name, UUID uploadId) {
    if (canvasStore.lockDocument(canvasId).isEmpty()) {
      throw new IllegalStateException("Canvas no longer exists: " + canvasId);
    }
    if (pinRepository.findRunningOutputPins(canvasId, resourceId).size() != 1) {
      throw new IllegalStateException(
          "Function target is no longer pinned by exactly one RUNNING run: " + resourceId);
    }
    StorageUploadService.ReadyUpload ready = uploadService.lockReady(uploadId);
    CanvasResource resource =
        new CanvasResource(
            resourceId, canvasId, null, null, ready.blobId(), name, null, Instant.now());
    if (!resourceRepository.addIfAbsent(resource)) {
      CanvasResource winner =
          resourceRepository
              .findById(canvasId, resourceId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "resource insertIfAbsent race: winner row is missing"));
      uploadService.delete(uploadId);
      return winner;
    }
    blobManager.retain(ready.blobId());
    uploadService.delete(uploadId);
    return resource;
  }

  private void discardBestEffort(UUID uploadId) {
    try {
      uploadService.delete(uploadId);
    } catch (RuntimeException ignored) {
      // Durable upload expiry remains the fallback.
    }
  }
}
