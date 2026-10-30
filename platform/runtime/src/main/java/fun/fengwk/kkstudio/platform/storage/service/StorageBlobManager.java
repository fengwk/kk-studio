package fun.fengwk.kkstudio.platform.storage.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.storage.S3PresignService;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageMaintenanceWakeup;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException;
import fun.fengwk.kkstudio.platform.storage.persistence.StorageBlobRepository;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

import java.util.Objects;
import java.util.UUID;

/**
 * blob 生命周期与预签名入口（全局 Blob 存储的显式 owner API）。
 *
 * <p>{@link #retain} 与 {@link #release} 必须运行在事务中（Spring {@code MANDATORY} 传播）： retain 仅对 ACTIVE 行生效
 * （DELETING 为终态，不可再 retain），release 将引用减一，减到 0 时在同一事务内切换到 DELETING， 并在事务提交后只唤醒本地后台维护线程；后台按
 * 预览→原始→行的顺序回收 S3 对象并条件删除行。计数 FK（{@code storage_upload.blob_id -> storage_blob.id}）为 RESTRICT，
 * 不会围绕本管理器发生级联删除。
 *
 * <p>所有状态切换由条件 UPDATE 在数据库行锁上串行化，应用层不持有长事务。DELETING 是终态（retain 只在 ACTIVE 行生效），因此后台清扫先在最短短事务内复核状态/引用
 * 并把待删对象登记为耐久清理记录，提交后才在事务外幂等删除对象；即便对象删除失败或之后又被迟到写入重建，清理记录也会让后台最终收敛，DELETING 永不反转回 ACTIVE。
 *
 * @author fengwk
 */
@Slf4j
public class StorageBlobManager {

  /** DELETING 清扫批次上限：后台维护据此判断是否已排空。 */
  public static final int MAX_SWEEP_BATCH = 16;

  private final StorageBlobRepository blobRepository;
  private final S3StorageService s3StorageService;
  private final S3PresignService s3PresignService;
  private final ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeups;
  private final StorageObjectCleanupService objectCleanupService;
  private final TransactionTemplate transactionTemplate;

  public StorageBlobManager(
      StorageBlobRepository blobRepository,
      S3StorageService s3StorageService,
      S3PresignService s3PresignService,
      ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeups,
      StorageObjectCleanupService objectCleanupService,
      PlatformTransactionManager transactionManager) {
    this.blobRepository = Objects.requireNonNull(blobRepository, "blobRepository");
    this.s3StorageService = Objects.requireNonNull(s3StorageService, "s3StorageService");
    this.s3PresignService = Objects.requireNonNull(s3PresignService, "s3PresignService");
    this.maintenanceWakeups = Objects.requireNonNull(maintenanceWakeups, "maintenanceWakeups");
    this.objectCleanupService =
        Objects.requireNonNull(objectCleanupService, "objectCleanupService");
    this.transactionTemplate =
        new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
  }

  /**
   * 事务内 retain：{@code ref_count + 1}。
   *
   * @return retain 后的 blob 行
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException blob 不存在或已
   *     DELETING
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public StorageBlob retain(UUID blobId) {
    Objects.requireNonNull(blobId, "blobId must not be null");
    if (!blobRepository.incrementRefCount(blobId)) {
      throw new StorageResourceNotFoundException("blob", blobId.toString());
    }
    StorageBlob blob = blobRepository.getById(blobId);
    if (blob == null) {
      throw new StorageResourceNotFoundException("blob", blobId.toString());
    }
    return blob;
  }

  /**
   * 事务内 release：{@code ref_count - 1}，减到 0 时切换 DELETING 并在提交后唤醒后台回收。
   *
   * @return true 表示本次调用释放了一个活跃引用；false 表示 blob 已 DELETING/不存在（幂等，调用方按已释放处理）
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean release(UUID blobId) {
    Objects.requireNonNull(blobId, "blobId must not be null");
    if (!blobRepository.releaseOnce(blobId)) {
      // 已 DELETING 或不存在：幂等视为已释放。
      return false;
    }
    // releaseOnce 在引用减到 0 的同一语句内切换到 DELETING（组合不变式在任何语句边界都成立）；
    // 读回状态决定是否注册提交后清理。
    StorageBlob blob = blobRepository.getById(blobId);
    if (blob != null && blob.getState() == StorageBlobState.DELETING) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              try {
                StorageMaintenanceWakeup wakeup = maintenanceWakeups.getObject();
                wakeup.wake();
              } catch (RuntimeException ignored) {
                // API 事务已经提交：本地唤醒只是低延迟提示，失败由 periodic poll 恢复且绝不向调用方冒泡。
                log.warn("storage maintenance wake failed for deleting blob {}", blobId);
              }
            }
          });
    }
    return true;
  }

  /** 读取 blob 行（不存在时返回 null；状态由调用方解释）。 */
  public StorageBlob getBlob(UUID blobId) {
    Objects.requireNonNull(blobId, "blobId must not be null");
    return blobRepository.getById(blobId);
  }

  /** 为 ACTIVE blob 生成原始内容 GET 预签名 URL（DELETING/不存在抛 404 语义异常）。 */
  public StoragePresignedUrlDTO presignOriginalUrl(UUID blobId) {
    StorageBlob blob = requireActive(blobId);
    StoragePresignedUrlDTO signed =
        StoragePresignedUrls.from(
            s3PresignService.presignDownload(StorageObjectKeys.blobOriginal(blobId), null));
    signed.setMediaType(blob.getMediaType());
    signed.setSizeBytes(blob.getSizeBytes());
    return signed;
  }

  /** 为 ACTIVE blob 生成预览 GET 预签名 URL（DELETING/不存在抛 404 语义异常）。 */
  public StoragePresignedUrlDTO presignPreviewUrl(UUID blobId) {
    requireActive(blobId);
    return StoragePresignedUrls.from(
        s3PresignService.presignDownload(StorageObjectKeys.blobPreview(blobId), null));
  }

  /** 后台维护用：回收残留 DELETING blob 的 S3 对象并条件删除其行，返回处理行数。 */
  public int sweepDeleting() {
    int swept = 0;
    for (UUID blobId : blobRepository.listDeletingIds(StorageBlobManager.MAX_SWEEP_BATCH)) {
      try {
        if (!registerDeletingObjects(blobId)) {
          continue;
        }
        deleteObjects(blobId);
        blobRepository.deleteDeleting(blobId);
        swept++;
      } catch (RuntimeException ignored) {
        log.warn("storage blob sweep failed for blob {}", blobId);
      }
    }
    return swept;
  }

  private StorageBlob requireActive(UUID blobId) {
    StorageBlob blob = blobRepository.getById(blobId);
    if (blob == null || blob.getState() != StorageBlobState.ACTIVE) {
      throw new StorageResourceNotFoundException("blob", blobId.toString());
    }
    return blob;
  }

  /**
   * 短事务复核 DELETING/零引用并把该 blob 的两个对象登记为耐久清理记录；只有提交成功才允许事务外删除对象， 因此对象删除不会与将来的迟到写入形成无事实残留，也不需要在 S3
   * I/O 期间持有事务。
   */
  private boolean registerDeletingObjects(UUID blobId) {
    Boolean registered =
        transactionTemplate.execute(
            status -> {
              StorageBlob blob = blobRepository.getById(blobId);
              if (blob == null
                  || blob.getState() != StorageBlobState.DELETING
                  || blob.getRefCount() != 0L) {
                return false;
              }
              objectCleanupService.enqueue(StorageObjectKeys.blobPreview(blobId));
              objectCleanupService.enqueue(StorageObjectKeys.blobOriginal(blobId));
              return true;
            });
    return Boolean.TRUE.equals(registered);
  }

  private void deleteObjects(UUID blobId) {
    // 先删预览再删原始：预览是最外层产物，原始对象保留到最后，行删除永远最后执行。
    s3StorageService.deleteObjectIfExists(StorageObjectKeys.blobPreview(blobId));
    s3StorageService.deleteObjectIfExists(StorageObjectKeys.blobOriginal(blobId));
  }
}
