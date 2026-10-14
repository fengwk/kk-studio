package fun.fengwk.kkstudio.platform.canvas.resource;

import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePin;
import fun.fengwk.kkstudio.canvas.CanvasFunctionResourcePinRepository;
import fun.fengwk.kkstudio.canvas.CanvasFunctionRunRepository;
import fun.fengwk.kkstudio.canvas.CanvasResource;
import fun.fengwk.kkstudio.canvas.CanvasResourceMaterializer;
import fun.fengwk.kkstudio.canvas.CanvasResourceRepository;
import fun.fengwk.kkstudio.canvas.CanvasStore;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageUploadService;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas 输出先在事务外通过 {@link StorageUploadService#stage} 准备为可恢复 upload，再于短事务内把 upload owner 转移给 Canvas
 * Resource。预览已经由统一上传服务在 Blob 绑定完成后生成。
 *
 * <p>Resource 行与 {@code OUTPUT} pin 必须同事务写入：pin 依赖真实 Resource 外键，不允许先写占位 pin。物化前先在文档锁内确认 {@code
 * (canvasId, nodeId, requestId, leaseToken)} 仍是当前 RUNNING owner，租约按数据库当前时间判定。 stage 前与 stage
 * 后均执行受围栏保护的短事务，S3/读流不持有 DB 锁；相同 resourceId 仅在 ownership 校验后幂等返回既有资源。并发竞争或事务失败会立即 best-effort 释放未消费
 * upload，统一过期清理仅作为兜底。TEXT 槽位不使用 upload/Blob，内容内联写在同一 Resource 行里。
 */
public class CanvasBlobResourceMaterializer implements CanvasResourceMaterializer {

  private static final long MAX_MATERIALIZE_SIZE = 512L * 1024 * 1024;
  private static final int MAX_MATERIALIZE_TEXT_LENGTH = 1024 * 1024;
  private static final String OCTET_STREAM = "application/octet-stream";

  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;
  private final CanvasStore canvasStore;
  private final CanvasFunctionRunRepository runRepository;
  private final CanvasFunctionResourcePinRepository pinRepository;
  private final CanvasResourceRepository resourceRepository;
  private final TransactionTemplate transactionTemplate;

  public CanvasBlobResourceMaterializer(
      StorageUploadService uploadService,
      StorageBlobManager blobManager,
      CanvasStore canvasStore,
      CanvasFunctionRunRepository runRepository,
      CanvasFunctionResourcePinRepository pinRepository,
      CanvasResourceRepository resourceRepository,
      TransactionTemplate transactionTemplate) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.canvasStore = Objects.requireNonNull(canvasStore, "canvasStore");
    this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
    this.pinRepository = Objects.requireNonNull(pinRepository, "pinRepository");
    this.resourceRepository = Objects.requireNonNull(resourceRepository, "resourceRepository");
    this.transactionTemplate = Objects.requireNonNull(transactionTemplate, "transactionTemplate");
  }

  @Override
  public CanvasResource materializeBlob(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      UUID resourceId,
      String name,
      InputStream content) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(content, "content");
    Objects.requireNonNull(leaseToken, "leaseToken");
    CanvasResource existing =
        transactionTemplate.execute(
            status -> {
              requireRunningRequest(canvasId, nodeId, requestId, leaseToken);
              return resourceRepository.findById(canvasId, resourceId).orElse(null);
            });
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
              status ->
                  materializeBlobInTransaction(
                      canvasId, nodeId, requestId, leaseToken, resourceId, name, uploadId));
    } catch (RuntimeException failure) {
      discardBestEffort(uploadId);
      throw failure;
    }
    return resource;
  }

  @Override
  public CanvasResource materializeText(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      UUID resourceId,
      String name,
      String text) {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(resourceId, "resourceId");
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(leaseToken, "leaseToken");
    if (text == null) {
      throw new IllegalArgumentException("text output content is required");
    }
    if (text.length() > MAX_MATERIALIZE_TEXT_LENGTH) {
      throw new IllegalArgumentException(
          "text output must be at most " + MAX_MATERIALIZE_TEXT_LENGTH + " characters");
    }
    return transactionTemplate.execute(
        status ->
            materializeTextInTransaction(
                canvasId, nodeId, requestId, leaseToken, resourceId, name, text));
  }

  private CanvasResource materializeBlobInTransaction(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      UUID resourceId,
      String name,
      UUID uploadId) {
    requireRunningRequest(canvasId, nodeId, requestId, leaseToken);
    CanvasResource winner = resourceRepository.findById(canvasId, resourceId).orElse(null);
    if (winner != null) {
      uploadService.delete(uploadId);
      return winner;
    }
    StorageUploadService.ReadyUpload ready = uploadService.lockReady(uploadId);
    CanvasResource resource =
        new CanvasResource(
            resourceId, canvasId, null, null, ready.blobId(), name, null, Instant.now());
    if (!resourceRepository.addIfAbsent(resource)) {
      CanvasResource existing =
          resourceRepository
              .findById(canvasId, resourceId)
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "resource insertIfAbsent race: winner row is missing"));
      uploadService.delete(uploadId);
      return existing;
    }
    addOutputPin(canvasId, nodeId, requestId, resourceId);
    blobManager.retain(ready.blobId());
    uploadService.delete(uploadId);
    return resource;
  }

  /** TEXT 槽位：内容内联在 Resource 行，与 OUTPUT pin 同事务写入，不涉及 upload 或 Blob 引用。 */
  private CanvasResource materializeTextInTransaction(
      UUID canvasId,
      UUID nodeId,
      UUID requestId,
      String leaseToken,
      UUID resourceId,
      String name,
      String text) {
    requireRunningRequest(canvasId, nodeId, requestId, leaseToken);
    CanvasResource winner = resourceRepository.findById(canvasId, resourceId).orElse(null);
    if (winner != null) {
      return winner;
    }
    CanvasResource resource =
        new CanvasResource(resourceId, canvasId, null, null, null, name, text, Instant.now());
    if (!resourceRepository.addIfAbsent(resource)) {
      return resourceRepository
          .findById(canvasId, resourceId)
          .orElseThrow(
              () ->
                  new IllegalStateException("resource insertIfAbsent race: winner row is missing"));
    }
    addOutputPin(canvasId, nodeId, requestId, resourceId);
    return resource;
  }

  private void addOutputPin(UUID canvasId, UUID nodeId, UUID requestId, UUID resourceId) {
    pinRepository.addAll(
        List.of(
            new CanvasFunctionResourcePin(
                canvasId, nodeId, requestId, resourceId, CanvasFunctionResourcePin.Role.OUTPUT)));
  }

  /** 在 document → run 锁内检查本次 owner；DB 时间检查发生在行锁取得后，避免锁等待掩盖租约到期。 */
  private void requireRunningRequest(
      UUID canvasId, UUID nodeId, UUID requestId, String leaseToken) {
    if (canvasStore.lockDocument(canvasId).isEmpty()) {
      throw new IllegalStateException("Canvas no longer exists: " + canvasId);
    }
    if (runRepository.findByNodeIdForUpdate(nodeId).isEmpty()
        || !runRepository.ownsRunningRequest(canvasId, nodeId, requestId, leaseToken)) {
      throw new IllegalStateException(
          "Function output can only be materialized by the live lease owner: " + nodeId);
    }
  }

  private void discardBestEffort(UUID uploadId) {
    try {
      uploadService.delete(uploadId);
    } catch (RuntimeException ignored) {
      // Durable upload expiry remains the fallback.
    }
  }
}
