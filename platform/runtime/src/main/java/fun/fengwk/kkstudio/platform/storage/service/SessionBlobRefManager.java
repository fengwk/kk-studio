package fun.fengwk.kkstudio.platform.storage.service;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.storage.persistence.SessionBlobRefRepository;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * {@code session_blob_ref} 的显式 owner API：Session 引用边的 insert/delete 与 blob retain/release 成对维护。
 *
 * <p>不变量：每个 ref 行恰好对应一次 {@link StorageBlobManager#retain}；删除时逐行 {@link
 * StorageBlobManager#release}。ref_count 维护绝不依赖 ON DELETE CASCADE 或触发器。所有变更方法必须运行在事务中 （Spring {@code
 * MANDATORY} 传播），由数据库行锁串行化并发 retain/release。
 *
 * <p>retainRef 先幂等插入 ref 行，只有新插入才 retain blob（每 ref 恰好一次 retain）；releaseRef 先删除 ref 行，只有实际删除才
 * release（每行恰好一次 release）。并发相同 (session, blob) 的 retainRef 由 {@code on conflict do nothing} 串行消解：赢家
 * retain，输家 no-op。
 */
public class SessionBlobRefManager {

  private final SessionBlobRefRepository refRepository;
  private final StorageBlobManager blobManager;

  public SessionBlobRefManager(
      SessionBlobRefRepository refRepository, StorageBlobManager blobManager) {
    this.refRepository = Objects.requireNonNull(refRepository, "refRepository");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  /**
   * 事务内 {@code retainRef}：ref 行不存在时插入并 retain 该 blob 恰好一次；ref 已存在时 no-op（同一 Session 复用同一 blob
   * 不重复计数）。命令路径消费 upload 前调用本方法，保证 upload release 之前新 ref 的 retain 已生效。
   *
   * @throws fun.fengwk.kkstudio.platform.storage.error.StorageResourceNotFoundException blob 不存在或已
   *     DELETING
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void retainRef(UUID sessionId, UUID blobId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(blobId, "blobId");
    if (refRepository.insertIfAbsent(sessionId, blobId)) {
      blobManager.retain(blobId);
    }
  }

  /** 事务内 {@code releaseRef}：ref 行存在时删除并 release 该 blob 恰好一次；不存在时 no-op（幂等）。 */
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseRef(UUID sessionId, UUID blobId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(blobId, "blobId");
    if (refRepository.delete(sessionId, blobId)) {
      blobManager.release(blobId);
    }
  }

  /**
   * 事务内只插入 ref 行（不 retain），返回是否新插入。仅供创建时已把该引用计入 {@code ref_count} 的新 blob 行配对使用，调用方必须保证与恰好一个 ref
   * 行同事务提交。
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean insertRefIfAbsent(UUID sessionId, UUID blobId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(blobId, "blobId");
    return refRepository.insertIfAbsent(sessionId, blobId);
  }

  /** 该 Session 是否已引用指定 blob。 */
  public boolean contains(UUID sessionId, UUID blobId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(blobId, "blobId");
    return refRepository.exists(sessionId, blobId);
  }

  /** 该 Session 引用的全部 blob id（Chat 深删除与 ref_count 对账用）。 */
  public List<UUID> listBlobIds(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    return refRepository.listBlobIds(sessionId);
  }
}
