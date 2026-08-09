package fun.fengwk.kkstudio.studio.canvas;

import java.time.Instant;
import java.util.Objects;

/** Canvas aggregate 的持久化头。 */
public record CanvasDocument(
    long id, String title, long graphRevision, Instant createdAt, Instant updatedAt) {

  public CanvasDocument {
    CanvasValidation.requirePositive(id, "id");
    CanvasValidation.requireNonBlank(title, "title");
    if (graphRevision < 0L) {
      throw new IllegalArgumentException("graphRevision must be >= 0");
    }
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(updatedAt, "updatedAt");
    if (updatedAt.isBefore(createdAt)) {
      throw new IllegalArgumentException("updatedAt must not be before createdAt");
    }
  }
}
