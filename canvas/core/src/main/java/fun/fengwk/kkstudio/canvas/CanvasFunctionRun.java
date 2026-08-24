package fun.fengwk.kkstudio.canvas;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Function 节点当前或最后一次运行。 */
public record CanvasFunctionRun(
    UUID nodeId,
    UUID requestId,
    CanvasFunctionRunStatus status,
    int attempt,
    Instant availableAt,
    String leaseToken,
    Instant leaseUntil,
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
    if ((leaseToken == null) != (leaseUntil == null)) {
      throw new IllegalArgumentException("leaseToken and leaseUntil must be both null or non-null");
    }
    if (leaseToken != null && (leaseToken.isBlank() || leaseToken.length() > 128)) {
      throw new IllegalArgumentException("leaseToken must contain 1 to 128 characters");
    }
    if (status == CanvasFunctionRunStatus.READY) {
      Objects.requireNonNull(availableAt, "READY availableAt");
      if (leaseToken != null) {
        throw new IllegalArgumentException("READY run must not hold a lease");
      }
    } else if (status == CanvasFunctionRunStatus.RUNNING) {
      Objects.requireNonNull(leaseToken, "RUNNING leaseToken");
      if (availableAt != null) {
        throw new IllegalArgumentException("RUNNING run must not have availableAt");
      }
    } else if (availableAt != null || leaseToken != null) {
      throw new IllegalArgumentException("terminal run must not be claimable or leased");
    }
    CanvasValidation.requireNonBlank(stage, "stage");
    CanvasValidation.requireNonBlank(stateJson, "stateJson");
    Objects.requireNonNull(updatedAt, "updatedAt");
    Objects.requireNonNull(createdAt, "createdAt");
  }
}
