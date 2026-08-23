package fun.fengwk.kkstudio.platform.storage.service.impl;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fun.fengwk.kkstudio.platform.storage.persistence.SessionBlobRefRepository;
import fun.fengwk.kkstudio.platform.storage.service.SessionBlobRefManager;
import fun.fengwk.kkstudio.platform.storage.service.StorageBlobManager;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 基于 PostgreSQL 的 {@link SessionBlobRefManager}。
 *
 * <p>retainRef/releaseRef 为 MANDATORY 事务方法：调用方必须处于事务中。retainRef 先幂等插入 ref 行，只有新插入才 retain blob（每
 * ref 恰好一次 retain）；releaseRef 先删除 ref 行，只有实际删除才 release（每行恰好一次 release）。 并发相同 (session, blob) 的
 * retainRef 由 {@code on conflict do nothing} 串行消解：赢家 retain，输家 no-op。
 */
public class PostgresqlSessionBlobRefManager implements SessionBlobRefManager {

  private final SessionBlobRefRepository refRepository;
  private final StorageBlobManager blobManager;

  public PostgresqlSessionBlobRefManager(
      SessionBlobRefRepository refRepository, StorageBlobManager blobManager) {
    this.refRepository = Objects.requireNonNull(refRepository, "refRepository");
    this.blobManager = Objects.requireNonNull(blobManager, "blobManager");
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void retainRef(UUID sessionId, UUID blobId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(blobId, "blobId");
    if (refRepository.insertIfAbsent(sessionId, blobId)) {
      blobManager.retain(blobId);
    }
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseRef(UUID sessionId, UUID blobId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(blobId, "blobId");
    if (refRepository.delete(sessionId, blobId)) {
      blobManager.release(blobId);
    }
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean insertRefIfAbsent(UUID sessionId, UUID blobId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(blobId, "blobId");
    return refRepository.insertIfAbsent(sessionId, blobId);
  }

  @Override
  public boolean contains(UUID sessionId, UUID blobId) {
    Objects.requireNonNull(sessionId, "sessionId");
    Objects.requireNonNull(blobId, "blobId");
    return refRepository.exists(sessionId, blobId);
  }

  @Override
  public List<UUID> listBlobIds(UUID sessionId) {
    Objects.requireNonNull(sessionId, "sessionId");
    return refRepository.listBlobIds(sessionId);
  }
}
