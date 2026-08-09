package fun.fengwk.kkstudio.studio.canvas;

/** Link 以 {@code (canvasId, sourceNodeId, targetNodeId)} 作为身份。 */
public record CanvasLink(long canvasId, long sourceNodeId, long targetNodeId) {

  public CanvasLink {
    CanvasValidation.requirePositive(canvasId, "canvasId");
    CanvasValidation.requirePositive(sourceNodeId, "sourceNodeId");
    CanvasValidation.requirePositive(targetNodeId, "targetNodeId");
    if (sourceNodeId == targetNodeId) {
      throw new IllegalArgumentException("sourceNodeId must differ from targetNodeId");
    }
  }
}
