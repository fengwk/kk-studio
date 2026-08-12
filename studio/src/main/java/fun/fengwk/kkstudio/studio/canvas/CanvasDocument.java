package fun.fengwk.kkstudio.studio.canvas;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas aggregate 的持久化头。
 *
 * <p>{@code threadId} 可空：Canvas 至多绑定一个根 Harness Thread，作为该画布的根级对话上下文。
 */
public record CanvasDocument(
    UUID id, String title, long version, UUID threadId, Instant createdAt, Instant updatedAt) {

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
