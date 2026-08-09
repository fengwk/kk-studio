package fun.fengwk.kkstudio.studio.canvas;

import java.time.Instant;
import java.util.Objects;

/** 尚未 finalize 的 Canvas upload 事实。 */
public record CanvasUpload(
    long id,
    long canvasId,
    CanvasResourceKind kind,
    String filename,
    String declaredMediaType,
    long declaredSize,
    Instant expiresAt,
    Instant createdAt) {

  public CanvasUpload {
    CanvasValidation.requirePositive(id, "id");
    CanvasValidation.requirePositive(canvasId, "canvasId");
    Objects.requireNonNull(kind, "kind");
    CanvasValidation.requireNonBlank(filename, "filename");
    CanvasValidation.requireNonBlank(declaredMediaType, "declaredMediaType");
    if (declaredSize < 0L) {
      throw new IllegalArgumentException("declaredSize must be >= 0");
    }
    Objects.requireNonNull(expiresAt, "expiresAt");
    Objects.requireNonNull(createdAt, "createdAt");
    if (!expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("expiresAt must be after createdAt");
    }
  }
}
