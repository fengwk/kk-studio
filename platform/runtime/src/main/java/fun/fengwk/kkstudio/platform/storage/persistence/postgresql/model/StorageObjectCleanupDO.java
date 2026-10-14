package fun.fengwk.kkstudio.platform.storage.persistence.postgresql.model;

import lombok.Data;

import java.time.Instant;

/** {@code storage_object_cleanup} 行映射（持久化层）。 */
@Data
public class StorageObjectCleanupDO {

  private String key;

  private Instant nextAttemptAt;
}
