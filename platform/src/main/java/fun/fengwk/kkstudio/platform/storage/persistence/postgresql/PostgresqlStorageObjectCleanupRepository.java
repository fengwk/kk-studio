package fun.fengwk.kkstudio.platform.storage.persistence.postgresql;

import lombok.AllArgsConstructor;
import org.springframework.stereotype.Repository;

import fun.fengwk.kkstudio.platform.storage.persistence.StorageObjectCleanupRepository;
import fun.fengwk.kkstudio.platform.storage.persistence.postgresql.mapper.StorageObjectCleanupMapper;

import java.util.List;

/** 基于 PostgreSQL 的 {@code storage_object_cleanup} 仓库。 */
@AllArgsConstructor
@Repository
public class PostgresqlStorageObjectCleanupRepository implements StorageObjectCleanupRepository {

  private final StorageObjectCleanupMapper cleanupMapper;

  @Override
  public boolean insertIfAbsent(String key) {
    // on conflict do nothing 时返回 0：key 已在登记事实中，仍然满足「对象被清理」的耐久证据。
    return cleanupMapper.insertIfAbsent(key) == 1;
  }

  @Override
  public List<String> claimDue(int limit, long delayMillis) {
    return cleanupMapper.claimDue(limit, delayMillis);
  }

  @Override
  public boolean reschedule(String key, long delayMillis) {
    return cleanupMapper.reschedule(key, delayMillis) == 1;
  }
}
