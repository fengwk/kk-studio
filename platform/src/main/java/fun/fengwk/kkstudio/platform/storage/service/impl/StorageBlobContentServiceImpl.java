package fun.fengwk.kkstudio.platform.storage.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.storage.S3ObjectContent;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobContentService;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;

import java.util.Objects;
import java.util.UUID;

/**
 * 基于 {@link StorageBlobManager} 与 {@link S3StorageService} 的 Blob 内容读取边界实现。
 *
 * <p>短事务 retain 权威 blob 并取得元数据； 事务外从 S3 下载原始对象内容； finally 短事务 release 释放引用。 任何 S3 / 网络 IO 均不在 DB
 * 事务中发生。
 *
 * @author fengwk
 */
@Slf4j
public class StorageBlobContentServiceImpl implements StorageBlobContentService {

  private final StorageBlobManager blobManager;
  private final S3StorageService s3StorageService;
  private final PlatformTransactionManager transactionManager;

  public StorageBlobContentServiceImpl(
      StorageBlobManager blobManager,
      S3StorageService s3StorageService,
      PlatformTransactionManager transactionManager) {
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.s3StorageService = Objects.requireNonNull(s3StorageService, "s3StorageService");
    this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
  }

  @Override
  public StorageBlobContent readBlobContent(UUID blobId, long maxSizeBytes) {
    Objects.requireNonNull(blobId, "blobId must not be null");

    // 1. 短事务 retain ACTIVE blob 并获取权威元数据
    TransactionTemplate retainTemplate = newTransactionTemplate();
    StorageBlob blob = retainTemplate.execute(status -> blobManager.retain(blobId));
    if (blob == null) {
      throw new IllegalStateException("blob retain returned null for " + blobId);
    }

    // 2. 事务外执行大小校验与 S3 下载；无论成功或失败，finally 短事务 release 释放引用
    try {
      if (maxSizeBytes >= 0 && blob.getSizeBytes() > maxSizeBytes) {
        throw new IllegalArgumentException(
            "blob size "
                + blob.getSizeBytes()
                + " bytes exceeds maximum allowed "
                + maxSizeBytes
                + " bytes");
      }
      String objectKey = StorageObjectKeys.blobOriginal(blobId);
      S3ObjectContent content = s3StorageService.download(objectKey, maxSizeBytes);
      return new StorageBlobContent(
          blobId, content.getBytes(), blob.getMediaType(), blob.getSizeBytes());
    } finally {
      // 3. 短事务 release
      try {
        TransactionTemplate releaseTemplate = newTransactionTemplate();
        releaseTemplate.execute(
            status -> {
              blobManager.release(blobId);
              return null;
            });
      } catch (RuntimeException error) {
        log.warn("failed to release blob {} in finally block", blobId, error);
      }
    }
  }

  private TransactionTemplate newTransactionTemplate() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }
}
