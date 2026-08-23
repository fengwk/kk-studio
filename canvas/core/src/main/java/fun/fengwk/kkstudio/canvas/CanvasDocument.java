package fun.fengwk.kkstudio.canvas;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas aggregate 的持久化头。
 *
 * <p>Harness 会话归属不落在此头上：Canvas 可以持有任意数量 {@link CanvasSession}（owner listing），线程本身由 Session
 * 关系枚举，本头不冗余存储 thread/session 引用。
 */
public record CanvasDocument(
    UUID id, String title, long version, Instant createdAt, Instant updatedAt) {

  public CanvasDocument {
    Objects.requireNonNull(id, "id");
    CanvasValidation.requireNonBlank(title, "title");
    if (version < 0L) {
      throw new IllegalArgumentException("version must be >= 0");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }
  }
}
