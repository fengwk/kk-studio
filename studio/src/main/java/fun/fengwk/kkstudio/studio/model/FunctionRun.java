package fun.fengwk.kkstudio.studio.model;

import java.util.Objects;

/** One Function execution instance. */
public record FunctionRun(
    long id,
    long workspaceId,
    Long canvasId,
    Long functionNodeId,
    FunctionRef functionRef,
    FunctionRunStatus status,
    long attempt,
    Long retryOfRunId,
    String idempotencyKey,
    long revision) {

  public FunctionRun {
    Objects.requireNonNull(functionRef, "functionRef");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }
}
