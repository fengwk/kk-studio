package fun.fengwk.kkstudio.platform.storage.service.impl;

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
    StorageBlobContent content = null;
    Throwable readError = null;
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
      S3ObjectContent s3Content = s3StorageService.download(objectKey, maxSizeBytes);
      content =
          new StorageBlobContent(
              blobId, s3Content.getBytes(), blob.getMediaType(), blob.getSizeBytes());
    } catch (Throwable error) {
      readError = error;
      throw error;
    } finally {
      // 3. 短事务 release：不重试非幂等 release；检查 release 返回值，失败时作为不变式破坏抛出
      try {
        TransactionTemplate releaseTemplate = newTransactionTemplate();
        releaseTemplate.execute(
            status -> {
              boolean released = blobManager.release(blobId);
              if (!released) {
                throw new IllegalStateException("blob release returned false for " + blobId);
              }
              return null;
            });
      } catch (RuntimeException | Error releaseError) {
        if (readError != null) {
          readError.addSuppressed(releaseError);
        } else {
          throw releaseError;
        }
      }
    }

    return content;
  }

  private TransactionTemplate newTransactionTemplate() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }
}
