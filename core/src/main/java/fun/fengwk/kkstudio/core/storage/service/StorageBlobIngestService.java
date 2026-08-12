package fun.fengwk.kkstudio.core.storage.service;

import java.util.UUID;

/**
 * 服务端字节内容的 blob 摄入契约：把内存字节物化为全局 Blob 存储的 ACTIVE blob，并与 Session 引用边配对。
 *
 * <p>用于 Tool/Daemon 瞬时 Resource 内容在 durable history 插入前的外部化。与浏览器直传路径（{@link
 * StorageUploadService}）不同，本入口没有 PENDING 阶段：字节已在服务端，内容立即物化。
 */
public interface StorageBlobIngestService {

  /**
   * 事务内摄入（Spring {@code MANDATORY} 传播）：计算 sha256/size，按 (sha256, size) 去重后返回 blobId，并保证 该 Session
   * 恰好持有一次引用：
   *
   * <ul>
   *   <li>命中既有 ACTIVE 行：{@code SessionBlobRefManager#retainRef}（ref 不存在才插入并 retain 一次）；
   *   <li>未命中：写入 S3 对象并以 {@code ref_count = 1} 创建新行，同事务配对 {@code
   *       SessionBlobRefManager#insertRefIfAbsent}（新行计数即该 ref 的 retain）。
   * </ul>
   *
   * <p>去重落败或事务回滚时，本实现会在事务完成后清理未引用的候选对象；调用方任何失败都让外层事务回滚，绝不产生部分引用。
   *
   * @throws IllegalArgumentException mediaType 非 canonical 或内容为空
   * @throws fun.fengwk.kkstudio.core.storage.error.StorageConflictException 去重未收敛
   */
  UUID ingest(UUID sessionId, byte[] bytes, String mediaType);
}
