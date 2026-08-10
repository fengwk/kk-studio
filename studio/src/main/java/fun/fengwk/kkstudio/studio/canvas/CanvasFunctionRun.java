package fun.fengwk.kkstudio.studio.canvas;

import java.time.Instant;
import java.util.Objects;

/** Function 节点当前或最后一次运行。 */
public record CanvasFunctionRun(
    long nodeId,
    String requestId,
    CanvasFunctionRunStatus status,
    String stage,
    String stateJson,
    String error,
    Instant updatedAt) {

  public CanvasFunctionRun {
    CanvasValidation.requirePositive(nodeId, "nodeId");
    CanvasValidation.requireNonBlank(requestId, "requestId");
    Objects.requireNonNull(status, "status");
    CanvasValidation.requireNonBlank(stage, "stage");
    CanvasValidation.requireNonBlank(stateJson, "stateJson");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
