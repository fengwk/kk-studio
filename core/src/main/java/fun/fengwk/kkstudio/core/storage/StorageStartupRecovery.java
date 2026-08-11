package fun.fengwk.kkstudio.core.storage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

import fun.fengwk.kkstudio.core.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.core.storage.service.StorageUploadService;

import java.util.Objects;

/**
 * 启动一次性恢复：应用就绪后排空过期上传与 DELETING blob 清扫。
 *
 * <p>没有周期性/后台 GC 线程：日常回收由 API 路径上的机会式批次承担，本恢复只兜底进程崩溃留下的过期上传与已标记 DELETING 但未完成 S3 清理的
 * blob。循环直到过期批次与清扫批次都低于各自上限（表示已排空）；单行失败在批内被吞掉并计为未处理，因此只要某批返回 0 或小于上限就会退出，
 * 重复失败不会造成死循环。全部为幂等操作，失败仅记录日志，不影响启动。
 *
 * @author fengwk
 */
@Slf4j
public class StorageStartupRecovery implements ApplicationListener<ApplicationReadyEvent> {

  private final StorageUploadService uploadService;
  private final StorageBlobManager blobManager;

  public StorageStartupRecovery(
      StorageUploadService uploadService, StorageBlobManager blobManager) {
    this.uploadService = Objects.requireNonNull(uploadService, "uploadService");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  @Override
  public void onApplicationEvent(ApplicationReadyEvent event) {
    try {
      int expired;
      int swept;
      do {
        expired = uploadService.expireOnce();
        swept = blobManager.sweepDeleting();
      } while (expired == StorageUploadService.MAX_EXPIRY_BATCH
          || swept == StorageBlobManager.MAX_SWEEP_BATCH);
      log.info(
          "storage startup recovery finished: expired uploads = {}, swept deleting blobs = {}",
          expired,
          swept);
    } catch (RuntimeException e) {
      log.error("storage startup recovery failed", e);
    }
  }
}
