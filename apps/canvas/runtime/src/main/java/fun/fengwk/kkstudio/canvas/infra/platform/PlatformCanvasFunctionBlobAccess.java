package fun.fengwk.kkstudio.canvas.infra.platform;

import fun.fengwk.kkstudio.canvas.BlobFacts;
import fun.fengwk.kkstudio.canvas.function.CanvasFunctionResourceStream;
import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.s3.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.service.DeadlineBoundedBlobStream;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Canvas Function Runtime 到 Platform 全局 Blob/S3 能力的适配器。 */
public final class PlatformCanvasFunctionBlobAccess {

  private final S3StorageService storageService;
  private final StorageBlobManager blobManager;
  private final Duration readBudget;

  public PlatformCanvasFunctionBlobAccess(
      S3StorageService storageService, StorageBlobManager blobManager, Duration readBudget) {
    this.storageService = Objects.requireNonNull(storageService, "storageService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.readBudget = Objects.requireNonNull(readBudget, "readBudget");
    if (readBudget.isNegative() || readBudget.isZero()) {
      throw new IllegalArgumentException("readBudget must be positive");
    }
  }

  public Optional<BlobFacts> findFacts(UUID blobId) {
    StorageBlob blob = blobManager.getBlob(Objects.requireNonNull(blobId, "blobId"));
    if (blob == null) {
      return Optional.empty();
    }
    return Optional.of(
        new BlobFacts(
            blob.getId(),
            blob.getMediaType(),
            blob.getSizeBytes(),
            blob.getWidth(),
            blob.getHeight(),
            blob.getDurationMs()));
  }

  /**
   * 打开冻结原件的平台受管读取流。
   *
   * <p>读取预算在打开时冻结，覆盖 S3 握手与响应体消费；到点或被取消时中断读取并 abort 连接。SPI 流关闭时若尚未读到 EOF 同样 abort，不排空剩余响应体。 Blob
   * 引用由 Canvas 的 {@code CanvasBlobReleaser} 管理，因此这里直接用平台读取流，不走 {@code withBlobStream} 的
   * retain/release 回调。
   */
  public CanvasFunctionResourceStream openOriginal(UUID blobId, long expectedSizeBytes) {
    DeadlineBoundedBlobStream object =
        DeadlineBoundedBlobStream.open(
            storageService, StorageObjectKeys.blobOriginal(blobId), ReadDeadline.after(readBudget));
    if (object.contentLength() != expectedSizeBytes) {
      // 未消费的响应体只能 abort，close 会排空剩余内容。
      object.close();
      throw new IllegalArgumentException("S3 original length does not match frozen blob size");
    }
    return new CanvasFunctionResourceStream(object, object.contentLength(), object);
  }
}
