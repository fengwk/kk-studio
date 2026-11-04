package fun.fengwk.kkstudio.canvas.infra.platform;

import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.util.Objects;
import java.util.UUID;

/** 在 Canvas 事务中释放 Resource 持有的 Blob 引用。 */
public class CanvasBlobReleaser {

  private final StorageBlobManager blobManager;

  public CanvasBlobReleaser(StorageBlobManager blobManager) {
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  /** 释放失败时抛出异常，使外层事务回滚；对象字节由 Storage 在事务外回收。 */
  public void release(UUID blobId) {
    if (!blobManager.release(blobId)) {
      throw new IllegalStateException("release canvas resource blob failed: " + blobId);
    }
  }
}
