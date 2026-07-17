package fun.fengwk.kkstudio.studio.runtime;

import fun.fengwk.kkstudio.studio.model.FunctionRef;

import java.util.Objects;

/**
 * Request to start a FunctionRun.
 *
 * <p>Input snapshot materialization and provider calls are adapter concerns and still TODO.
 */
public record FunctionExecutionRequest(
    long workspaceId,
    Long canvasId,
    Long functionNodeId,
    FunctionRef functionRef,
    String configSnapshotJson,
    Long configRevision,
    String idempotencyKey) {

  public FunctionExecutionRequest {
    Objects.requireNonNull(functionRef, "functionRef");
    Objects.requireNonNull(configSnapshotJson, "configSnapshotJson");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
  }
}
