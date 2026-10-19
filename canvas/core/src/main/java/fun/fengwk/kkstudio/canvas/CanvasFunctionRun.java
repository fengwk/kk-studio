package fun.fengwk.kkstudio.canvas;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Function 节点当前或最后一次运行；调度与执行所有权在共享的 durable work 中，不属于 Run 本身。 */
public record CanvasFunctionRun(
    UUID nodeId,
    UUID requestId,
    CanvasFunctionRunStatus status,
    int attempt,
    String stage,
    String stateJson,
    String error,
    Instant updatedAt,
    Instant createdAt) {

  public CanvasFunctionRun {
    Objects.requireNonNull(nodeId, "nodeId");
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(status, "status");
    if (attempt < 0) {
      throw new IllegalArgumentException("attempt must be non-negative");
    }
    if (status == CanvasFunctionRunStatus.FAILED || status == CanvasFunctionRunStatus.UNKNOWN) {
      CanvasValidation.requireNonBlank(error, status + " error");
    } else if (error != null && status != CanvasFunctionRunStatus.CANCELLED) {
      throw new IllegalArgumentException(status + " run must not carry an error");
    }
    CanvasValidation.requireNonBlank(stage, "stage");
    CanvasValidation.requireNonBlank(stateJson, "stateJson");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
