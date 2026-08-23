package fun.fengwk.kkstudio.canvas;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Function 节点当前或最后一次运行。 */
public record CanvasFunctionRun(
    UUID nodeId,
    UUID requestId,
    CanvasFunctionRunStatus status,
    String stage,
    String stateJson,
    String error,
    Instant updatedAt) {

  public CanvasFunctionRun {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(status, "status");
    CanvasValidation.requireNonBlank(stage, "stage");
    CanvasValidation.requireNonBlank(stateJson, "stateJson");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }
}
