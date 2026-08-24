package fun.fengwk.kkstudio.platform.storage.persistence.postgresql.model;

import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/** {@code storage_upload} 行映射（持久化层）。 */
@Data
public class StorageUploadDO {

  private UUID id;

  private UUID candidateBlobId;

  private UUID blobId;

  private String filename;

  private String declaredMediaType;

  private long declaredSize;

  private String declaredSha256;

  private Instant expiresAt;

  private String cleanupToken;

  private Instant cleanupUntil;

  private Instant createTime;
}
