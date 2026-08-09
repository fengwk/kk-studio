package fun.fengwk.kkstudio.studio.canvas;

import java.util.Objects;

/** 不嵌套、使用 world 坐标的 Canvas group。 */
public record CanvasGroup(long id, long canvasId, String title, CanvasTransform transform) {

  public CanvasGroup {
    CanvasValidation.requirePositive(id, "id");
    CanvasValidation.requirePositive(canvasId, "canvasId");
    CanvasValidation.requireNonBlank(title, "title");
    Objects.requireNonNull(transform, "transform");
  }
}
