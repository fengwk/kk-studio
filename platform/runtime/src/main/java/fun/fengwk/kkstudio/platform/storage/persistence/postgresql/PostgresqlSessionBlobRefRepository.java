package fun.fengwk.kkstudio.platform.storage.persistence.postgresql;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.storage.persistence.SessionBlobRefRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.postgresql.store.SessionBlobRefJdbcStore;

import java.util.List;
import java.util.UUID;

/** 基于 PostgreSQL 的 {@code session_blob_ref} 仓库。 */
@AllArgsConstructor
@Repository
public class PostgresqlSessionBlobRefRepository implements SessionBlobRefRepository {

  private final SessionBlobRefJdbcStore sessionBlobRefJdbcStore;

  @Override
  public boolean insertIfAbsent(UUID sessionId, UUID blobId) {
    return sessionBlobRefJdbcStore.insertIfAbsent(sessionId, blobId) == 1;
  }

  @Override
  public boolean delete(UUID sessionId, UUID blobId) {
    return sessionBlobRefJdbcStore.delete(sessionId, blobId) == 1;
  }

  @Override
  public boolean exists(UUID sessionId, UUID blobId) {
    return sessionBlobRefJdbcStore.exists(sessionId, blobId);
  }

  @Override
  public List<UUID> listBlobIds(UUID sessionId) {
    return sessionBlobRefJdbcStore.listBlobIds(sessionId);
  }
}
