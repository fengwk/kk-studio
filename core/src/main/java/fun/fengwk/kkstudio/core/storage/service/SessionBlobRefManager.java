package fun.fengwk.kkstudio.core.storage.service;

import java.util.List;
import java.util.UUID;

/**
 * {@code session_blob_ref} 的显式 owner API：Session 引用边的 insert/delete 与 blob retain/release 成对维护。
 *
 * <p>不变量：每个 ref 行恰好对应一次 {@link StorageBlobManager#retain}；删除时逐行 {@link
 * StorageBlobManager#release}。ref_count 维护绝不依赖 ON DELETE CASCADE 或触发器。所有变更方法必须运行在事务中 （Spring {@code
 * MANDATORY} 传播），由数据库行锁串行化并发 retain/release。
 */
public interface SessionBlobRefManager {

  /**
   * 事务内 {@code retainRef}：ref 行不存在时插入并 retain 该 blob 恰好一次；ref 已存在时 no-op（同一 Session 复用同一 blob
   * 不重复计数）。命令路径消费 upload 前调用本方法，保证 upload release 之前新 ref 的 retain 已生效。
   *
   * @throws fun.fengwk.kkstudio.core.storage.error.StorageResourceNotFoundException blob 不存在或已
   *     DELETING
   */
  void retainRef(UUID sessionId, UUID blobId);

  /** 事务内 {@code releaseRef}：ref 行存在时删除并 release 该 blob 恰好一次；不存在时 no-op（幂等）。 */
  void releaseRef(UUID sessionId, UUID blobId);

  /**
   * 事务内只插入 ref 行（不 retain），返回是否新插入。仅供 {@code StorageBlobIngestService} 的新行路径配对使用：新 blob 行 创建时
   * {@code ref_count = 1} 已计入该 ref，调用方必须保证与恰好一个 ref 行同事务提交。
   */
  boolean insertRefIfAbsent(UUID sessionId, UUID blobId);

  /** 该 Session 是否已引用指定 blob。 */
  boolean contains(UUID sessionId, UUID blobId);

  /** 该 Session 引用的全部 blob id（Chat 深删除与 ref_count 对账用）。 */
  List<UUID> listBlobIds(UUID sessionId);
}
