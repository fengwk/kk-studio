package fun.fengwk.kkstudio.platform.storage.service.impl;

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
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageObjectCleanupService;
import fun.fengwk.kkstudio.platform.storage.service.StoragePresignedUrls;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobState;
import fun.fengwk.kkstudio.share.storage.StoragePresignedUrlDTO;

import java.util.Objects;
import java.util.UUID;

/**
 * 基于 PostgreSQL 的 {@link StorageBlobManager}。
 *
 * <p>retain/release 为 MANDATORY 事务方法：调用方必须处于事务中（服务层用 {@code TransactionTemplate} 包裹）， 所有状态切换由条件
 * UPDATE 在数据库行锁上串行化。release 将引用减到 0 时在同一事务内切换到 DELETING，提交后只快速唤醒本地 Storage Maintenance；S3
 * 对象与行由后台按预览→原始→条件删行处理。
 *
 * <p>DELETING 是终态（retain 只在 ACTIVE 行生效），因此后台清扫先在最短短事务内复核状态/引用并把待删对象登记为耐久清理记录，
 * 提交后才在事务外幂等删除对象；即便对象删除失败或之后又被迟到写入重建，清理记录也会让后台最终收敛，DELETING 永不反转回 ACTIVE。
 *
 * @author fengwk
 */
@Slf4j
public class PostgresqlStorageBlobManager implements StorageBlobManager {

  private final StorageBlobRepository blobRepository;
  private final S3StorageService s3StorageService;
  private final S3PresignService s3PresignService;
  private final ObjectProvider<StorageMaintenanceWakeup> maintenanceWakeups;
  private final StorageObjectCleanupService objectCleanupService;
  private final TransactionTemplate transactionTemplate;

  public PostgresqlStorageBlobManager(
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

  @Override
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

  @Override
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

  @Override
  public StorageBlob getBlob(UUID blobId) {
    Objects.requireNonNull(blobId, "blobId must not be null");
    return blobRepository.getById(blobId);
  }

  @Override
  public StoragePresignedUrlDTO presignOriginalUrl(UUID blobId) {
    StorageBlob blob = requireActive(blobId);
    StoragePresignedUrlDTO signed =
        StoragePresignedUrls.from(
            s3PresignService.presignDownload(StorageObjectKeys.blobOriginal(blobId), null));
    signed.setMediaType(blob.getMediaType());
    signed.setSizeBytes(blob.getSizeBytes());
    return signed;
  }

  @Override
  public StoragePresignedUrlDTO presignPreviewUrl(UUID blobId) {
    requireActive(blobId);
    return StoragePresignedUrls.from(
        s3PresignService.presignDownload(StorageObjectKeys.blobPreview(blobId), null));
  }

  @Override
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
