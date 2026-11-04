package fun.fengwk.kkstudio.canvas;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Canvas aggregate 的持久化头。
 *
 * <p>{@code revision} 只用于同步排序、补漏和确认接受位置，不是普通编辑的整图前置版本；它随已提交的变化单调前进。
 */
public record CanvasDocument(
    UUID id, String title, long revision, Instant createdAt, Instant updatedAt) {

  public CanvasDocument {
    Objects.requireNonNull(id, "id");
    CanvasValidation.requireNonBlank(title, "title");
    if (revision < 0L) {
      throw new IllegalArgumentException("revision must be >= 0");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }
  }
}
