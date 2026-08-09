package fun.fengwk.kkstudio.studio.canvas;

import java.time.Instant;
import java.util.Objects;

/** 已完成校验并持久化的不可变资源。 */
public record CanvasResource(
    long id,
    long canvasId,
    CanvasResourceKind kind,
    String mediaType,
    String name,
    long size,
    String textContent,
    String metadataJson,
    Instant createdAt) {

  public CanvasResource {
    CanvasValidation.requirePositive(id, "id");
    CanvasValidation.requirePositive(canvasId, "canvasId");
    Objects.requireNonNull(kind, "kind");
    CanvasValidation.requireNonBlank(mediaType, "mediaType");
    CanvasValidation.requireNonBlank(name, "name");
    if (size < 0L) {
      throw new IllegalArgumentException("size must be >= 0");
    }
    if (kind == CanvasResourceKind.TEXT && textContent == null) {
      throw new IllegalArgumentException("textContent is required for TEXT resource");
    }
    if (kind != CanvasResourceKind.TEXT && textContent != null) {
      throw new IllegalArgumentException("textContent is only allowed for TEXT resource");
    }
    CanvasValidation.requireNonBlank(metadataJson, "metadataJson");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
