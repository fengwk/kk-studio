package fun.fengwk.kkstudio.core.storage.persistence.postgresql.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code storage_blob} 行映射（持久化层）。 */
@Data
public class StorageBlobDO {

  private UUID id;

  private String sha256;

  private long sizeBytes;

  private String mediaType;

  private Long width;

  private Long height;

  private Long durationMs;

  private long refCount;

  private String state;

  private Instant createTime;

  private Instant updateTime;
}
