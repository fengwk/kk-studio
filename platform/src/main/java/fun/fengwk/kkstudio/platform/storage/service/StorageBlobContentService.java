package fun.fengwk.kkstudio.platform.storage.service;

import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;

import java.util.UUID;

/**
 * 最小的 Blob 内容读取边界接口。
 *
 * <p>短事务 retain ACTIVE blob 并取得权威元数据； 事务外通过原始对象 key 下载内容； finally 短事务 release 释放引用。 任何 S3 或外部 IO
 * 均不得发生在 DB 事务或行锁中。
 *
 * @author fengwk
 */
public interface StorageBlobContentService {

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
  StorageBlobContent readBlobContent(UUID blobId, long maxSizeBytes);
}
