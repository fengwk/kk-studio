package fun.fengwk.kkstudio.studio.canvas;

import java.util.Objects;
import java.util.UUID;

/** Link 以 {@code (canvasId, sourceNodeId, targetNodeId)} 作为身份。 */
public record CanvasLink(UUID canvasId, UUID sourceNodeId, UUID targetNodeId) {

  public CanvasLink {
    Objects.requireNonNull(canvasId, "canvasId");
    Objects.requireNonNull(sourceNodeId, "sourceNodeId");
    Objects.requireNonNull(targetNodeId, "targetNodeId");
    if (Objects.equals(sourceNodeId, targetNodeId)) {
      throw new IllegalArgumentException("sourceNodeId must differ from targetNodeId");
    }
  }
}
