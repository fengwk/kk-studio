package fun.fengwk.kkstudio.platform.storage.persistence.postgresql;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.storage.persistence.SessionBlobRefRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.postgresql.mapper.SessionBlobRefMapper;

import java.util.List;
import java.util.UUID;

/** 基于 PostgreSQL 的 {@code session_blob_ref} 仓库。 */
@AllArgsConstructor
@Repository
public class PostgresqlSessionBlobRefRepository implements SessionBlobRefRepository {

  private final SessionBlobRefMapper mapper;

  @Override
  public boolean insertIfAbsent(UUID sessionId, UUID blobId) {
    return mapper.insertIfAbsent(sessionId, blobId) == 1;
  }

  @Override
  public boolean delete(UUID sessionId, UUID blobId) {
    return mapper.delete(sessionId, blobId) == 1;
  }

  @Override
  public boolean exists(UUID sessionId, UUID blobId) {
    return mapper.exists(sessionId, blobId);
  }

  @Override
  public List<UUID> listBlobIds(UUID sessionId) {
    return mapper.listBlobIds(sessionId);
  }
}
