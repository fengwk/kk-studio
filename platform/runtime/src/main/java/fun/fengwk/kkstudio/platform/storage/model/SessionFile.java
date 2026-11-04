package fun.fengwk.kkstudio.platform.storage.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Session 级文件元数据记录：表示 Session 虚拟文件系统中的一个具体文件事实。
 *
 * @param sessionId 所属 Harness Session
 * @param path 文件在 Session 空间内的相对规范路径
 * @param blobId 指向全局存储的权威 Blob ID
 * @param revision 乐观锁版本号（严格大于 0）
 * @param updatedAt 最后更新时间戳
 */
public record SessionFile(
    UUID sessionId, String path, UUID blobId, int revision, Instant updatedAt) {

  public SessionFile {
    Objects.requireNonNull(sessionId, "sessionId must not be null");
    Objects.requireNonNull(path, "path must not be null");
    Objects.requireNonNull(blobId, "blobId must not be null");
    Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    if (revision <= 0) {
      throw new IllegalArgumentException("revision must be positive: " + revision);
    }
  }
}
