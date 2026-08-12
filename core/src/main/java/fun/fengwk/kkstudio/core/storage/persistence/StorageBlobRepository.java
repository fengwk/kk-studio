package fun.fengwk.kkstudio.core.storage.persistence;

import fun.fengwk.kkstudio.core.storage.service.model.StorageBlob;

import java.util.List;
import java.util.UUID;

/** {@code storage_blob} 持久化契约：去重、引用计数与两阶段删除的原子操作。 */
public interface StorageBlobRepository {

  StorageBlob getById(UUID id);

  StorageBlob getActiveByHashAndSize(String sha256, long sizeBytes);

  /**
   * 以完整事实插入 ACTIVE 候选行（{@code ref_count = 1}，complete 时调用）。 与 ACTIVE 行同内容的并发插入由部分唯一索引消解， 本方法返回
   * false 表示插入被消解，调用方应改读现有 ACTIVE 行并自行 retain。
   */
  boolean insertActiveCandidate(StorageBlob blob);

  /** 原子 retain：仅当行仍为 ACTIVE 时 {@code ref_count + 1}，否则返回 false。 */
  boolean incrementRefCount(UUID id);

  /**
   * 原子 release：仅当 ACTIVE 且 {@code ref_count > 0} 时减一；减到 0 的同一语句内切换到 DELETING
   * （状态与引用计数组合不变式在任何语句边界都成立），返回是否发生释放。
   */
  boolean releaseOnce(UUID id);

  /** 条件删除 DELETING 行：仅在 S3 对象已删除后调用（{@code ref_count = 0} 兜底）。 */
  boolean deleteDeleting(UUID id);

  /** 启动恢复用：列出待回收的 DELETING 行 id（上限 {@code limit}）。 */
  List<UUID> listDeletingIds(int limit);
}
