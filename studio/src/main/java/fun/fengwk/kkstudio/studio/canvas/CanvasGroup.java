package fun.fengwk.kkstudio.studio.canvas;

import java.util.Objects;
import java.util.UUID;

/** 不嵌套、使用 world 坐标的 Canvas group。 */
public record CanvasGroup(UUID id, UUID canvasId, String title, CanvasTransform transform) {

  public CanvasGroup {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(canvasId, "canvasId");
    CanvasValidation.requireNonBlank(title, "title");
    Objects.requireNonNull(transform, "transform");
  }
}
