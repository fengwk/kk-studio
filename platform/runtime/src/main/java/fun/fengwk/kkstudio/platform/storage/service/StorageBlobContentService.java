package fun.fengwk.kkstudio.platform.storage.service;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.S3StorageService;
import fun.fengwk.kkstudio.platform.storage.StorageObjectKeys;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlob;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * 最小的 Blob 内容读取边界服务。
 *
 * <p>短事务 retain 权威 blob 并取得元数据；事务外从 S3 下载或流式读取原始对象；关闭流后在独立短事务中 release。 任何 S3 / 网络 IO 均不在 DB 事务中发生。
 *
 * @author fengwk
 */
public class StorageBlobContentService {

  private final StorageBlobManager blobManager;
  private final S3StorageService s3StorageService;
  private final PlatformTransactionManager transactionManager;

  public StorageBlobContentService(
      StorageBlobManager blobManager,
      S3StorageService s3StorageService,
      PlatformTransactionManager transactionManager) {
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
    this.s3StorageService = Objects.requireNonNull(s3StorageService, "s3StorageService");
    this.transactionManager = Objects.requireNonNull(transactionManager, "transactionManager");
  }

  /**
   * 安全读取 ACTIVE blob 的原始内容与权威元数据。
   *
   * @param blobId 目标 blob ID
   * @param maxSizeBytes 最大允许读取字节数（负数表示不限）
   * @return 权威 blob 内容与元数据
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException 当 blob
   *     不存在或已非 ACTIVE 时抛出
   * @throws IllegalArgumentException 当 blob 大小超过最大允许限制时抛出
   */
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
      // 契约：负数表示不限大小。有界下载对任何负上限都会先被大小断言拒绝，因此负数必须走无上限的单参数下载。
      byte[] bytes =
          maxSizeBytes < 0
              ? s3StorageService.download(objectKey)
              : s3StorageService.download(objectKey, maxSizeBytes).getBytes();
      content = new StorageBlobContent(blobId, bytes, blob.getMediaType(), blob.getSizeBytes());
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

  /**
   * 在短事务 retain/release 之间提供原始对象流；consumer 在事务外执行，流不能逃逸回调。
   *
   * <p>{@code deadline} 由调用方在读取请求入口冻结，覆盖 S3 握手与响应体消费的总预算；到点或读取线程被中断时中止连接并抛出可区分的失败，不排空剩余响应体。
   *
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageReadTimeoutException 读取超过截止时间
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageReadInterruptedException 读取被取消
   */
  public <T> T withBlobStream(
      UUID blobId, ReadDeadline deadline, Function<InputStream, T> consumer) {
    Objects.requireNonNull(blobId, "blobId");
    Objects.requireNonNull(deadline, "deadline");
    Objects.requireNonNull(consumer, "consumer");
    StorageBlob blob = newTransactionTemplate().execute(status -> blobManager.retain(blobId));
    if (blob == null) {
      throw new IllegalStateException("blob retain returned null for " + blobId);
    }
    Throwable readError = null;
    try (DeadlineBoundedBlobStream stream =
        DeadlineBoundedBlobStream.open(
            s3StorageService, StorageObjectKeys.blobOriginal(blobId), deadline)) {
      return consumer.apply(stream);
    } catch (RuntimeException | Error e) {
      readError = e;
      throw e;
    } finally {
      try {
        newTransactionTemplate()
            .execute(
                status -> {
                  if (!blobManager.release(blobId)) {
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
  }

  private TransactionTemplate newTransactionTemplate() {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    return template;
  }
}
