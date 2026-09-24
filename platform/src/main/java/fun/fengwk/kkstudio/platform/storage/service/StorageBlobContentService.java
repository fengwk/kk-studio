package fun.fengwk.kkstudio.platform.storage.service;

import fun.fengwk.kkstudio.platform.storage.ReadDeadline;
import fun.fengwk.kkstudio.platform.storage.service.model.StorageBlobContent;

import java.io.InputStream;
import java.util.UUID;
import java.util.function.Function;

/**
 * 最小的 Blob 内容读取边界接口。
 *
 * <p>短事务 retain ACTIVE blob 并取得权威元数据；事务外通过原始对象 key 读取内容；流关闭后在独立短事务中 release。 任何 S3 或外部 IO 均不得发生在 DB
 * 事务或行锁中。
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

  /**
   * 在短事务 retain/release 之间提供原始对象流；consumer 在事务外执行，流不能逃逸回调。
   *
   * <p>{@code deadline} 由调用方在读取请求入口冻结，覆盖 S3 握手与响应体消费的总预算；到点或读取线程被中断时中止连接并抛出可区分的失败，不排空剩余响应体。
   *
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageReadTimeoutException 读取超过截止时间
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageReadInterruptedException 读取被取消
   */
  <T> T withBlobStream(UUID blobId, ReadDeadline deadline, Function<InputStream, T> consumer);
}
